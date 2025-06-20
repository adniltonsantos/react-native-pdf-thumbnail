package org.songsterq.pdfthumbnail

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Random

class PdfThumbnailModule(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String {
    return NAME
  }

  @ReactMethod
  fun generate(filePath: String, page: Int, quality: Int, promise: Promise) {
    var parcelFileDescriptor: ParcelFileDescriptor? = null
    var pdfRenderer: PdfRenderer? = null
    try {
      parcelFileDescriptor = getParcelFileDescriptor(filePath)
      if (parcelFileDescriptor == null) {
        promise.reject("FILE_NOT_FOUND", "File $filePath not found")
        return
      }

      pdfRenderer = PdfRenderer(parcelFileDescriptor)
      if (page < 0 || page >= pdfRenderer.pageCount) {
        promise.reject("INVALID_PAGE", "Page number $page is invalid, file has ${pdfRenderer.pageCount} pages")
        return
      }

      val result = renderPage(pdfRenderer, page, filePath, quality)
      promise.resolve(result)
    } catch (ex: IOException) {
      promise.reject("INTERNAL_ERROR", ex)
    } finally {
      pdfRenderer?.close()
      parcelFileDescriptor?.close()
    }
  }

  @ReactMethod
  fun generateAllPages(filePath: String, quality: Int, promise: Promise) {
    var parcelFileDescriptor: ParcelFileDescriptor? = null
    var pdfRenderer: PdfRenderer? = null
    try {
      parcelFileDescriptor = getParcelFileDescriptor(filePath)
      if (parcelFileDescriptor == null) {
        promise.reject("FILE_NOT_FOUND", "File $filePath not found")
        return
      }

      pdfRenderer = PdfRenderer(parcelFileDescriptor)
      val result = WritableNativeArray()
      for (page in 0 until pdfRenderer.pageCount) {
        result.pushMap(renderPage(pdfRenderer, page, filePath, quality))
      }
      promise.resolve(result)
    } catch (ex: IOException) {
      promise.reject("INTERNAL_ERROR", ex)
    } finally {
      pdfRenderer?.close()
      parcelFileDescriptor?.close()
    }
  }

  private fun getParcelFileDescriptor(filePath: String): ParcelFileDescriptor? {
    val uri = Uri.parse(filePath)
    if (ContentResolver.SCHEME_CONTENT == uri.scheme || ContentResolver.SCHEME_FILE == uri.scheme) {
      return this.reactApplicationContext.contentResolver.openFileDescriptor(uri, "r")
    } else if (filePath.startsWith("/")) {
      val file = File(filePath)
      return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    return null
  }

  private fun renderPage(pdfRenderer: PdfRenderer, page: Int, filePath: String, quality: Int): WritableNativeMap {
    val currentPage = pdfRenderer.openPage(page)
    val width = currentPage.width
    val height = currentPage.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    currentPage.close()

    // Some bitmaps have transparent background which results in a black thumbnail. Add a white background.
    val bitmapWhiteBG = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmapWhiteBG)
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    bitmap.recycle()

    val outputFile = File.createTempFile(getOutputFilePrefix(filePath, page), ".jpg", reactApplicationContext.cacheDir)
    if (outputFile.exists()) {
      outputFile.delete()
    }
    val out = FileOutputStream(outputFile)
    bitmapWhiteBG.compress(Bitmap.CompressFormat.JPEG, quality, out)
    bitmapWhiteBG.recycle()
    out.flush()
    out.close()

    val map = WritableNativeMap()
    map.putString("uri", Uri.fromFile(outputFile).toString())
    map.putInt("width", width)
    map.putInt("height", height)
    return map
  }

  private fun getOutputFilePrefix(filePath: String, page: Int): String {
    val tokens = filePath.split("/")
    val originalFilename = tokens[tokens.lastIndex]
    val prefix = originalFilename.replace(".", "-")
    val generator = Random()
    val random = generator.nextInt(Integer.MAX_VALUE)
    return "$prefix-thumbnail-$page-$random"
  }

  companion object {
    const val NAME = "PdfThumbnail"
  }
}
