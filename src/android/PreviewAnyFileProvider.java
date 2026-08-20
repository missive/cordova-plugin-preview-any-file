package com.missiveapp.previewanyfile;

import androidx.core.content.FileProvider;

/**
 * Android keys ContentProvider records by class, not by authority, so two <provider> entries both
 * naming androidx.core.content.FileProvider collapse into one and only the first authority ever
 * gets published — and cordova-plugin-file already declares one. The unpublished authority still
 * resolves far enough to build URIs and grant permissions on them, then fails every read with
 * "Permission Denial: ... not exported", which viewer apps report as an empty document.
 * Subclassing gives this plugin's provider a component of its own so both survive.
 */
public class PreviewAnyFileProvider extends FileProvider {
}
