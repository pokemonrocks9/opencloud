/* openCloud Android Library is available under MIT license
 *   Copyright (C) 2020 ownCloud GmbH.
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 *   EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 *   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 *   NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 *   BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 *   ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 *   CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 *
 */
package eu.opencloud.android.lib.resources.files.search

import android.net.Uri
import android.util.Xml
import eu.opencloud.android.lib.common.OpenCloudClient
import eu.opencloud.android.lib.common.http.methods.nonwebdav.ReportMethod
import eu.opencloud.android.lib.common.operations.RemoteOperation
import eu.opencloud.android.lib.common.operations.RemoteOperationResult
import eu.opencloud.android.lib.common.operations.RemoteOperationResult.ResultCode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Server-side search across all spaces (OpenCloud search service), e.g.
 * pattern = mediatype:("spreadsheet") AND is:favorite
 */
class SearchFilesRemoteOperation(
    private val pattern: String,
    private val limit: Int = 200,
) : RemoteOperation<List<SearchFilesRemoteOperation.SearchResult>>() {

    data class SearchResult(
        val fileId: String,
        val name: String,
        /** path inside its space, e.g. /Work/Budget.xlsx */
        val path: String,
        val spaceId: String,
        val mimeType: String,
        val lastModified: Long,
        val isFavorite: Boolean,
    )

    override fun run(client: OpenCloudClient): RemoteOperationResult<List<SearchResult>> =
        try {
            val body = """<?xml version="1.0" encoding="UTF-8"?>
<oc:search-files xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:prop>
    <oc:fileid/>
    <oc:name/>
    <oc:favorite/>
    <d:getlastmodified/>
    <d:getcontenttype/>
    <d:resourcetype/>
  </d:prop>
  <oc:search>
    <oc:pattern>${escapeXml(pattern)}</oc:pattern>
    <oc:limit>$limit</oc:limit>
  </oc:search>
</oc:search-files>""".toRequestBody("application/xml; charset=utf-8".toMediaType())

            val url = URL(client.baseUri.toString().trimEnd('/') + SPACES_DAV_PATH)
            val method = ReportMethod(url, body).apply {
                setReadTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                setConnectionTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            }
            val status = client.executeHttpMethod(method)
            if (status == HTTP_MULTI_STATUS || status == HTTP_OK) {
                RemoteOperationResult<List<SearchResult>>(ResultCode.OK).apply {
                    data = parse(method.getResponseBodyAsString())
                }
            } else {
                Timber.w("Search failed with status %d", status)
                RemoteOperationResult<List<SearchResult>>(method).apply { data = emptyList() }
            }
        } catch (e: Exception) {
            Timber.e(e, "Search failed")
            RemoteOperationResult<List<SearchResult>>(e)
        }

    private fun parse(xml: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(xml.reader())
        }
        var href = ""
        var fileId = ""
        var name = ""
        var mime = ""
        var modified = 0L
        var favorite = false
        var isFolder = false
        var text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':')
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (tag) {
                        "response" -> {
                            href = ""; fileId = ""; name = ""; mime = ""
                            modified = 0L; favorite = false; isFolder = false
                        }
                        "collection" -> isFolder = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val value = text.toString().trim()
                    when (tag) {
                        "href" -> href = value
                        "fileid" -> fileId = value
                        "name" -> name = value
                        "getcontenttype" -> mime = value
                        "favorite" -> favorite = value == "1"
                        "getlastmodified" -> modified = parseDate(value)
                        "response" -> toResult(href, fileId, name, mime, modified, favorite)
                            ?.takeIf { !isFolder }
                            ?.let { results.add(it) }
                    }
                }
            }
            event = parser.next()
        }
        return results
    }

    private fun toResult(
        href: String, fileId: String, name: String, mime: String, modified: Long, favorite: Boolean
    ): SearchResult? {
        if (fileId.isEmpty()) return null
        // href: /remote.php/dav/spaces/<spaceId>/<path...>
        val decoded = Uri.decode(href)
        val afterSpaces = decoded.substringAfter("/dav/spaces/", "")
        val spaceId = afterSpaces.substringBefore('/')
        val path = "/" + afterSpaces.substringAfter('/', "")
        return SearchResult(
            fileId = fileId,
            name = name.ifEmpty { path.substringAfterLast('/') },
            path = path,
            spaceId = spaceId,
            mimeType = mime,
            lastModified = modified,
            isFavorite = favorite,
        )
    }

    private fun parseDate(value: String): Long =
        try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }

    private fun escapeXml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val SPACES_DAV_PATH = "/remote.php/dav/spaces/"
        private const val HTTP_OK = 200
        private const val HTTP_MULTI_STATUS = 207
        private const val TIMEOUT = 15_000L
    }
}
