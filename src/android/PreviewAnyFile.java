package com.missiveapp.previewanyfile;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PreviewAnyFile extends CordovaPlugin {

  private static final String TAG = "PreviewAnyFile";

  private static final String FALLBACK_MIME_TYPE = "application/*";
  private static final String CACHE_DIR_NAME = "preview-any-files";
  private static final int PREVIEW_REQUEST_CODE = 1;

  // Downloads outlive the intent that handed them off: the viewer app may still be reading the
  // file through its content URI long after we are done with it.
  private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;
  private static final int CACHE_MAX_ENTRIES = 20;

  private static final int CONNECT_TIMEOUT_MS = 15000;
  private static final int READ_TIMEOUT_MS = 30000;
  private static final int MAX_REDIRECTS = 5;
  // HttpURLConnection has no constants for these two.
  private static final int HTTP_TEMPORARY_REDIRECT = 307;
  private static final int HTTP_PERMANENT_REDIRECT = 308;
  private static final int BUFFER_SIZE = 8192;

  private static final Pattern DATA_URL_MIME_TYPE = Pattern.compile("^data:([a-zA-Z0-9]+/[a-zA-Z0-9.+-]+).*,.*");
  private static final Pattern UNSAFE_FILE_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\x00]+");

  // The callback for the preview we are waiting on an activity result for, captured at launch so
  // two previews in quick succession can't hand each other's result to the wrong caller.
  private CallbackContext pendingPreview;

  private static boolean notEmpty(String what) {
    return what != null && !"".equals(what) && !"null".equalsIgnoreCase(what);
  }

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    cordova.getThreadPool().execute(new Runnable() {
      @Override
      public void run() {
        try {
          switch (action) {
            case "preview":
              preview(args.getString(0), callbackContext);
              break;
            case "previewPath":
              previewPath(args.getString(0), args.getString(1), args.getString(2), callbackContext);
              break;
            case "previewBase64":
              previewBase64(args.getString(0), args.getString(1), args.getString(2), callbackContext);
              break;
            default:
              returnResult(callbackContext, Status.ERROR,
                  "Method " + action + " not Exist, only preview,previewPath and previewBase64 are allowed");
              break;
          }
        } catch (Exception e) {
          Log.e(TAG, "preview failed", e);
          returnResult(callbackContext, Status.ERROR, e.getLocalizedMessage());
        }
      }
    });

    returnResult(callbackContext, Status.NO_RESULT, null);
    return true;
  }

  private void preview(String url, CallbackContext callbackContext) throws IOException, URISyntaxException {
    previewPath(url, null, null, callbackContext);
  }

  private void previewPath(String path, String name, String mediaType, CallbackContext callbackContext)
      throws IOException, URISyntaxException {
    String mimeType = notEmpty(mediaType) ? mediaType : pathToMime(notEmpty(name) ? name : path);

    if (isRemoteUrl(path)) {
      // Download first, the way the iOS side does. An https URI in an ACTION_VIEW intent that also
      // sets a mime type matches nothing: viewer apps declare content:// or file:// plus a type,
      // browsers declare https with no type at all, and Android needs both to match.
      viewFile(fileToUri(downloadToCache(path, name, mimeType)), mimeType, callbackContext);
    } else {
      viewFile(pathToUri(path), mimeType, callbackContext);
    }
  }

  private void previewBase64(String base64, String name, String mediaType, CallbackContext callbackContext)
      throws IOException {
    String mimeType = notEmpty(mediaType) ? mediaType : null;
    String encoded = base64;

    if (base64.startsWith("data:")) {
      // content is not a valid base64
      if (!base64.contains(";base64,")) {
        returnResult(callbackContext, Status.ERROR, "content is not a valid base64");
        return;
      }
      // data urls look like this: data:image/png;base64,R0lGODlhDAA...
      if (!notEmpty(mimeType))
        mimeType = base64ToMime(base64);
      encoded = base64.substring(base64.indexOf(";base64,") + 8);
    } else if (!notEmpty(mimeType)) {
      mimeType = pathToMime(name);
    }

    if (!notEmpty(mimeType)) {
      returnResult(callbackContext, Status.ERROR, "You must specify either file name with extension or MimeType");
      return;
    }

    File file = new File(newCacheDir(), cacheFileName(null, name, mimeType));
    writeFile(Base64.decode(encoded, Base64.DEFAULT), file);
    viewFile(fileToUri(file), mimeType, callbackContext);
  }

  private void viewFile(final Uri uri, String mimeType, final CallbackContext callbackContext) {
    final String type = notEmpty(mimeType) ? mimeType : FALLBACK_MIME_TYPE;

    cordova.getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        // No chooser: Android already puts up its own disambiguation dialog when several apps
        // match and no default is set, and wrapping this in one would make every launch succeed,
        // costing us the only reliable "nothing can open this" signal there is.
        if (launch(uri, type, callbackContext))
          return;
        if (!FALLBACK_MIME_TYPE.equals(type) && launch(uri, FALLBACK_MIME_TYPE, callbackContext))
          return;

        // Neither the real type nor the catch-all resolved, so the device genuinely has nothing
        // for this file. A normal outcome, not an error — the client turns it into an offer to
        // open the file in the browser instead.
        Log.i(TAG, "no activity for " + uri + " (" + type + ")");
        returnResult(callbackContext, Status.OK, "NO_APP");
      }
    });
  }

  /** @return false when no activity could handle the intent, which never throws past this point. */
  private boolean launch(Uri uri, String mimeType, CallbackContext callbackContext) {
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.setDataAndType(uri, mimeType);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

    try {
      cordova.setActivityResultCallback(this);
      cordova.getActivity().startActivityForResult(intent, PREVIEW_REQUEST_CODE);
    } catch (ActivityNotFoundException e) {
      // The exception type is the signal. Upstream matched on getLocalizedMessage() instead, so
      // this branch never ran on a non-English device and NPE'd when the message was null.
      return false;
    }

    pendingPreview = callbackContext;
    returnResult(callbackContext, Status.OK, "SUCCESS");
    return true;
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    CallbackContext preview = this.pendingPreview;
    this.pendingPreview = null;

    if (requestCode == PREVIEW_REQUEST_CODE && preview != null)
      returnResult(preview, Status.OK, "CLOSING");

    super.onActivityResult(requestCode, resultCode, intent);
  }

  private Uri pathToUri(String path) throws URISyntaxException {
    if (path.startsWith("file:"))
      return fileToUri(new File(new URI(path)));
    return Uri.parse(path);
  }

  private Uri fileToUri(File file) {
    return FileProvider.getUriForFile(cordova.getActivity(),
        cordova.getActivity().getPackageName() + ".fileprovider", file);
  }

  private File downloadToCache(String url, String name, String mimeType) throws IOException {
    File file = new File(newCacheDir(), cacheFileName(url, name, mimeType));
    download(url, file);
    return file;
  }

  private void download(String url, File destination) throws IOException {
    HttpURLConnection connection = null;
    String location = url;

    try {
      for (int redirects = 0;; redirects++) {
        connection = (HttpURLConnection) new URL(location).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.connect();

        int status = connection.getResponseCode();
        if (!isRedirect(status)) {
          if (status < 200 || status > 299)
            throw new IOException("DOWNLOAD_FAILED_" + status);
          break;
        }

        if (redirects >= MAX_REDIRECTS)
          throw new IOException("TOO_MANY_REDIRECTS");
        String next = connection.getHeaderField("Location");
        if (!notEmpty(next))
          throw new IOException("REDIRECT_WITHOUT_LOCATION");

        // Resolves relative redirects, and follows http <-> https, which HttpURLConnection refuses
        // to do on its own however setInstanceFollowRedirects is set.
        location = new URL(new URL(location), next).toString();
        connection.disconnect();
        connection = null;
      }

      InputStream input = null;
      OutputStream output = null;
      try {
        input = new BufferedInputStream(connection.getInputStream());
        output = new FileOutputStream(destination);

        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1)
          output.write(buffer, 0, read);
        output.flush();
      } finally {
        closeQuietly(output);
        closeQuietly(input);
      }
    } catch (IOException e) {
      // noinspection ResultOfMethodCallIgnored
      destination.delete();
      throw e;
    } finally {
      if (connection != null)
        connection.disconnect();
    }
  }

  private static boolean isRedirect(int status) {
    return status == HttpURLConnection.HTTP_MOVED_PERM
        || status == HttpURLConnection.HTTP_MOVED_TEMP
        || status == HttpURLConnection.HTTP_SEE_OTHER
        || status == HTTP_TEMPORARY_REDIRECT
        || status == HTTP_PERMANENT_REDIRECT;
  }

  private static void writeFile(byte[] bytes, File destination) throws IOException {
    OutputStream output = new FileOutputStream(destination);
    try {
      output.write(bytes);
      output.flush();
    } finally {
      closeQuietly(output);
    }
  }

  /**
   * A fresh directory per preview, inside the app cache. Cache storage is already covered by
   * file_paths.xml and, unlike external storage, is always available. One directory per file keeps
   * the file's real name intact without two previews of "invoice.pdf" colliding.
   */
  private File newCacheDir() throws IOException {
    File root = new File(cordova.getActivity().getCacheDir(), CACHE_DIR_NAME);
    if (!root.exists() && !root.mkdirs())
      throw new IOException("CREATE_DIRS_FAILED");

    pruneCache(root);

    File dir = new File(root, UUID.randomUUID().toString());
    if (!dir.mkdirs())
      throw new IOException("CREATE_DIRS_FAILED");
    return dir;
  }

  private static void pruneCache(File root) {
    File[] entries = root.listFiles();
    if (entries == null)
      return;

    long expiry = System.currentTimeMillis() - CACHE_TTL_MS;
    for (File entry : entries) {
      if (entry.lastModified() < expiry)
        deleteRecursively(entry);
    }

    entries = root.listFiles();
    if (entries == null || entries.length <= CACHE_MAX_ENTRIES)
      return;

    Arrays.sort(entries, new Comparator<File>() {
      @Override
      public int compare(File a, File b) {
        return Long.compare(a.lastModified(), b.lastModified());
      }
    });
    for (int i = 0; i < entries.length - CACHE_MAX_ENTRIES; i++)
      deleteRecursively(entries[i]);
  }

  private static void deleteRecursively(File entry) {
    File[] children = entry.listFiles();
    if (children != null) {
      for (File child : children)
        deleteRecursively(child);
    }
    // noinspection ResultOfMethodCallIgnored
    entry.delete();
  }

  private static String cacheFileName(String url, String name, String mimeType) {
    String fileName = notEmpty(name) ? name : lastPathSegment(url);
    fileName = UNSAFE_FILE_NAME_CHARS.matcher(fileName).replaceAll("_").trim();
    if (!notEmpty(fileName) || ".".equals(fileName) || "..".equals(fileName))
      fileName = "file";

    // Spaces and accents are left alone on purpose: the file is handed to FileProvider as a File,
    // never round-tripped through java.net.URI, which throws on both.
    if (fileName.lastIndexOf('.') <= 0 && notEmpty(mimeType)) {
      String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
      if (notEmpty(extension))
        fileName = fileName + "." + extension;
    }
    return fileName;
  }

  private static String lastPathSegment(String url) {
    if (!notEmpty(url))
      return "";
    String segment = Uri.parse(url).getLastPathSegment();
    return notEmpty(segment) ? segment : "";
  }

  private static boolean isRemoteUrl(String path) {
    String lower = path.toLowerCase(Locale.US);
    return lower.startsWith("http://") || lower.startsWith("https://");
  }

  private static String base64ToMime(String encoded) {
    Matcher matcher = DATA_URL_MIME_TYPE.matcher(encoded);
    return matcher.find() ? matcher.group(1).toLowerCase(Locale.US) : null;
  }

  private static String pathToMime(String path) {
    if (!notEmpty(path))
      return null;

    String extension = MimeTypeMap.getFileExtensionFromUrl(path);
    if (!notEmpty(extension))
      extension = fileExtension(path);
    if (!notEmpty(extension))
      return null;

    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.US));
  }

  /**
   * MimeTypeMap.getFileExtensionFromUrl matches the name against [a-zA-Z_0-9.\-()%]+, so it finds
   * nothing as soon as there is a space or an accent in it, which covers most real file names.
   */
  private static String fileExtension(String path) {
    String name = path;
    int query = name.indexOf('?');
    if (query >= 0)
      name = name.substring(0, query);
    int fragment = name.indexOf('#');
    if (fragment >= 0)
      name = name.substring(0, fragment);
    int slash = name.lastIndexOf('/');
    if (slash >= 0)
      name = name.substring(slash + 1);

    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(dot + 1) : null;
  }

  private static void closeQuietly(Closeable closeable) {
    if (closeable == null)
      return;
    try {
      closeable.close();
    } catch (IOException e) {
      Log.w(TAG, "failed to close stream", e);
    }
  }

  private void returnResult(CallbackContext callbackContext, Status status, String message) {
    PluginResult pluginResult = message == null ? new PluginResult(status) : new PluginResult(status, message);
    pluginResult.setKeepCallback(true);
    callbackContext.sendPluginResult(pluginResult);
  }
}
