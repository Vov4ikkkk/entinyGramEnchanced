@file:Suppress("DEPRECATION")

package desu.inugram.helpers.media

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ChatAttachAlert
import org.telegram.ui.Components.ChatAttachAlertPhotoLayout
import org.telegram.ui.Components.GalleryEmptyView
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil

object MediaPickerHelper {
    @JvmStatic
    fun useNativePicker(alert: ChatAttachAlert?): Boolean =
        InuConfig.NATIVE_MEDIA_PICKER.value && alert != null && alert.avatarPicker == 0 && alert.baseFragment is ChatActivity

    @JvmStatic
    fun skipFilesPermission(alert: ChatAttachAlert?): Boolean =
        useNativePicker(alert) && Build.VERSION.SDK_INT >= 29 && !BuildVars.NO_SCOPED_STORAGE

    @JvmStatic
    fun launchNativePicker(alert: ChatAttachAlert?): Boolean {
        if (alert == null || !useNativePicker(alert)) return false
        if (!startNativePicker(alert.baseFragment as ChatActivity, alert.maxSelectedPhotos)) return false
        alert.dismiss(true)
        return true
    }

    @JvmStatic
    fun launchNativePicker(fragment: ChatActivity): Boolean =
        InuConfig.NATIVE_MEDIA_PICKER.value && startNativePicker(fragment, -1)

    @JvmStatic
    fun bindEmptyView(alert: ChatAttachAlert?, view: GalleryEmptyView) {
        if (!useNativePicker(alert)) return
        view.inu_setTexts(
            LocaleController.getString(R.string.InuNativeMediaPickerTitle),
            LocaleController.getString(R.string.InuNativeMediaPickerText),
            LocaleController.getString(R.string.InuNativeMediaPickerButton),
        )
    }

    private fun startNativePicker(fragment: ChatActivity, max: Int): Boolean {
        return try {
            fragment.startActivityForResult(nativePickerIntent(max), 1)
            true
        } catch (e: Exception) {
            FileLog.e(e)
            false
        }
    }

    @SuppressLint("NewApi")
    private fun nativePickerIntent(max: Int): Intent {
        if (hasSystemPhotoPicker()) {
            val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
            if (max != 1) {
                val limit = MediaStore.getPickImagesMaxLimit()
                intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, if (max in 2..limit) max else limit)
            }
            return intent
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, max != 1)
        return intent
    }

    private fun hasSystemPhotoPicker(): Boolean = Build.VERSION.SDK_INT >= 33 ||
        Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2

    @JvmStatic
    fun launchPicker(alert: ChatAttachAlert): Boolean {
        if (alert.baseFragment !is ChatActivity) return false
        try {
            val videoPickerIntent = Intent(Intent.ACTION_GET_CONTENT)
            videoPickerIntent.type = "video/*"
            videoPickerIntent.putExtra(MediaStore.EXTRA_SIZE_LIMIT, FileLoader.DEFAULT_MAX_FILE_SIZE)
            videoPickerIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

            val photoPickerIntent = Intent(Intent.ACTION_PICK)
            photoPickerIntent.type = "image/*"
            photoPickerIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

            val chooserIntent = Intent.createChooser(photoPickerIntent, null)
            val extraIntents = ArrayList<Intent>()
            if (Build.VERSION.SDK_INT >= 33) {
                val systemPickerIntent = Intent(MediaStore.ACTION_PICK_IMAGES)
                val limit = MediaStore.getPickImagesMaxLimit()
                val max = alert.maxSelectedPhotos
                if (max < 0 || max > 1) {
                    systemPickerIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, if (max in 2..limit) max else limit)
                }
                extraIntents.add(systemPickerIntent)
            }
            extraIntents.add(videoPickerIntent)
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
            alert.baseFragment.startActivityForResult(chooserIntent, 1)
        } catch (e: Exception) {
            FileLog.e(e)
            return false
        }
        alert.dismiss(true)
        return true
    }

    @JvmStatic
    fun handlePickerResult(fragment: ChatActivity, data: Intent?): Boolean {
        if (data == null) return false
        val uris = ArrayList<Uri>()
        val clipData = data.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        if (uris.isEmpty()) {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return false
        Utilities.globalQueue.postRunnable {
            val entries = ArrayList<MediaController.PhotoEntry>()
            for (uri in uris) {
                try {
                    buildEntry(uri)?.let { entries.add(it) }
                } catch (e: Exception) {
                    FileLog.e(e)
                }
            }
            AndroidUtilities.runOnUIThread { presentEntries(fragment, entries) }
        }
        return true
    }

    private fun buildEntry(uri: Uri): MediaController.PhotoEntry? {
        val mime = ApplicationLoader.applicationContext.contentResolver.getType(uri) ?: ""
        val isVideo = mime.startsWith("video/")
        val ext = when {
            isVideo -> "mp4"
            mime == "image/png" -> "png"
            mime == "image/webp" -> "webp"
            mime == "image/gif" -> "gif"
            else -> "jpg"
        }
        val path = MediaController.copyFileToCache(uri, ext, if (isVideo) FileLoader.DEFAULT_MAX_FILE_SIZE else -1L)
            ?: return null
        return if (isVideo) buildVideoEntry(path) else buildPhotoEntry(path)
    }

    private fun buildPhotoEntry(path: String): MediaController.PhotoEntry {
        val orientation = AndroidUtilities.getImageOrientation(path)
        var width = 0
        var height = 0
        try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, options)
            width = options.outWidth
            height = options.outHeight
        } catch (_: Exception) {
        }
        val entry = MediaController.PhotoEntry(
            0, ChatAttachAlertPhotoLayout.lastImageId--, 0L, path,
            orientation.first, false, width, height, 0L,
        ).setOrientation(orientation)
        entry.canDeleteAfter = true
        return entry
    }

    private fun buildVideoEntry(path: String): MediaController.PhotoEntry? {
        var duration = 0
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let {
                duration = ceil(it.toLong() / 1000.0).toInt()
            }
        } catch (e: Exception) {
            FileLog.e(e)
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }
        val bitmap = SendMessagesHelper.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
            ?: return null
        val fileName = Integer.MIN_VALUE.toString() + "_" + SharedConfig.getLastLocalId() + ".jpg"
        val cacheFile = File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), fileName)
        try {
            FileOutputStream(cacheFile).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 55, stream)
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        SharedConfig.saveConfig()
        val entry = MediaController.PhotoEntry(
            0, ChatAttachAlertPhotoLayout.lastImageId--, 0L, path,
            0, true, bitmap.width, bitmap.height, 0L,
        )
        entry.duration = duration
        entry.thumbPath = cacheFile.absolutePath
        entry.canDeleteAfter = true
        return entry
    }

    private fun presentEntries(fragment: ChatActivity, entries: List<MediaController.PhotoEntry>) {
        if (fragment.parentActivity == null) return
        if (entries.isEmpty()) {
            BulletinFactory.of(fragment)
                .createErrorBulletin(LocaleController.getString(R.string.UnsupportedAttachment))
                .show()
            return
        }
        fragment.createChatAttachView()
        val alert = fragment.chatAttachAlert ?: return
        val layout = alert.photoLayout
        var trimmed = entries
        if (alert.maxSelectedPhotos in 0 until entries.size) {
            trimmed = entries.subList(0, alert.maxSelectedPhotos)
        }
        if (trimmed.isEmpty()) return
        ChatAttachAlertPhotoLayout.mediaFromExternalCamera = true
        ChatAttachAlertPhotoLayout.inu_openIndex = ChatAttachAlertPhotoLayout.cameraPhotos.size
        for (i in 0 until trimmed.size - 1) {
            val entry = trimmed[i]
            ChatAttachAlertPhotoLayout.cameraPhotos.add(entry)
            ChatAttachAlertPhotoLayout.selectedPhotos[entry.imageId] = entry
            ChatAttachAlertPhotoLayout.selectedPhotosOrder.add(entry.imageId)
        }
        layout.openPhotoViewer(trimmed[trimmed.size - 1], false, true)
    }
}
