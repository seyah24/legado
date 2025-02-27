package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.RemoteBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.readBytes
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File

object RemoteBookWebDav : RemoteBookManager() {
    val rootBookUrl get() = "${AppWebDav.rootWebDavUrl}${remoteBookFolder}"

    init {
        runBlocking {
            initRemoteContext()
        }
    }

    override suspend fun initRemoteContext() {
        AppWebDav.authorization?.let {
            WebDav(rootBookUrl, it).makeAsDir()
        }
    }

    @Throws(Exception::class)
    override suspend fun getRemoteBookList(path: String): MutableList<RemoteBook> {
        val remoteBooks = mutableListOf<RemoteBook>()
        AppWebDav.authorization?.let {
            //读取文件列表
            val remoteWebDavFileList: List<WebDavFile> = WebDav(path, it).listFiles()
            //转化远程文件信息到本地对象
            remoteWebDavFileList.forEach { webDavFile ->
                if (webDavFile.isDir) {
                    remoteBooks.add(
                        RemoteBook(
                            webDavFile.displayName, webDavFile.path, webDavFile.size,
                            "folder", webDavFile.lastModify
                        )
                    )
                } else {
                    //分割后缀
                    val fileExtension = webDavFile.displayName.substringAfterLast(".")

                    //扩展名符合阅读的格式则认为是书籍
                    if (bookFileRegex.matches(webDavFile.displayName)) {
                        val isOnBookShelf = LocalBook.isOnBookShelf(webDavFile.displayName)
                        remoteBooks.add(
                            RemoteBook(
                                webDavFile.displayName, webDavFile.path, webDavFile.size,
                                fileExtension, webDavFile.lastModify, isOnBookShelf
                            )
                        )
                    }
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return remoteBooks
    }

    override suspend fun getRemoteBook(remoteBook: RemoteBook): Uri {
        return AppWebDav.authorization?.let {
            val webdav = WebDav(remoteBook.path, it)
            webdav.downloadInputStream().let { inputStream ->
                LocalBook.saveBookFile(inputStream, remoteBook.filename)
            }
        } ?: throw NoStackTraceException("webDav没有配置")
    }

    override suspend fun upload(book: Book) {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val localBookUri = Uri.parse(book.bookUrl)
        val putUrl = "$rootBookUrl${File.separator}${book.originName}"
        AppWebDav.authorization?.let {
            if (localBookUri.isContentScheme()) {
                WebDav(putUrl, it).upload(
                    byteArray = localBookUri.readBytes(appCtx),
                    contentType = "application/octet-stream"
                )
            } else {
                WebDav(putUrl, it).upload(localBookUri.path!!)
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        book.origin = BookType.webDavTag + putUrl
        book.save()
    }

    override suspend fun delete(remoteBookUrl: String) {
        AppWebDav.authorization?.let {
            WebDav(remoteBookUrl, it).delete()
        } ?: throw NoStackTraceException("webDav没有配置")
    }

}