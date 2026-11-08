package com.vpn4tv.app.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * The pictures of the home screen channel's cards (QuickConnectChannel): the
 * TV launcher, another app, loads a card's picture by its address. Read only,
 * and only the PNG files the app drew for the channel (flags, Auto); nothing
 * else of the app is reachable through it.
 */
class ChannelArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val context = context ?: throw FileNotFoundException()
        val name = uri.pathSegments.singleOrNull()?.takeIf { NAME.matches(it) } ?: throw FileNotFoundException(uri.toString())
        val file = File(dir(context), name)
        if (!file.isFile) throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val NAME = Regex("^[a-z0-9-]{1,40}\\.png$")

        /** Where the pictures are drawn: this device's, not backed up. */
        fun dir(context: Context): File = File(context.noBackupFilesDir, "tv_channel_art").apply { mkdirs() }

        fun uri(context: Context, name: String): Uri =
            Uri.Builder().scheme("content").authority("${context.packageName}.channelart").appendPath(name).build()
    }
}
