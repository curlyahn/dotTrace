package com.dot.trace.export

import com.dot.trace.data.PointEntity
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 기록을 GPX 파일로 내보낸다. 구글 어스, 지도 앱, 등산 앱 등에서 열 수 있는 표준 형식. */
object GpxExporter {

    fun write(points: List<PointEntity>, name: String, out: OutputStream) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            w.newLine()
            w.write("""<gpx version="1.1" creator="DotTrace" xmlns="http://www.topografix.com/GPX/1/1">""")
            w.newLine()
            w.write("  <trk><name>${escape(name)}</name><trkseg>")
            w.newLine()
            for (p in points) {
                w.write("""    <trkpt lat="${p.lat}" lon="${p.lng}">""")
                p.altitude?.let { w.write("<ele>$it</ele>") }
                w.write("<time>${iso.format(Date(p.time))}</time>")
                w.write("</trkpt>")
                w.newLine()
            }
            w.write("  </trkseg></trk>")
            w.newLine()
            w.write("</gpx>")
            w.newLine()
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
