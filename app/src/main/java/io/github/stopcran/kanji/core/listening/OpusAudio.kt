package io.github.stopcran.kanji.core.listening

import java.io.ByteArrayOutputStream

/** Bounded Ogg framing and mono Opus envelope checks; not a claim about audible linguistic correctness. */
object OpusAudio {
    const val MAX_BYTES = 512 * 1024
    private val crcTable = IntArray(256) { byte ->
        var crc = byte shl 24
        repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        crc
    }

    fun durationMs(data: ByteArray, minimumMs: Long = 15_000, maximumMs: Long = 60_000): Long {
        require(minimumMs >= 250 && maximumMs in minimumMs..90_000) { "Invalid Opus duration envelope" }
        require(data.size in 1..MAX_BYTES) { "Audio exceeds the 512 KiB envelope" }
        var offset = 0
        var sequence = 0L
        var serial: Long? = null
        var lastGranule = 0L
        var ended = false
        var audioPackets = 0
        val packets = mutableListOf<ByteArray>()
        val pending = ByteArrayOutputStream()
        while (offset < data.size) {
            require(!ended && offset + 27 <= data.size) { "Truncated or trailing Ogg data" }
            require(String(data, offset, 4, Charsets.US_ASCII) == "OggS" && data[offset + 4] == 0.toByte()) { "Audio is not Ogg" }
            val flags = data[offset + 5].toInt() and 255
            require(flags and 7.inv() == 0 && (flags and 2 != 0) == (sequence == 0L)) { "Invalid Ogg flags" }
            require((flags and 1 != 0) == (pending.size() > 0)) { "Invalid Ogg packet continuation" }
            val currentSerial = little(data, offset + 14, 4)
            require(serial == null || serial == currentSerial) { "Chained Ogg streams are unsupported" }
            serial = currentSerial
            require(little(data, offset + 18, 4) == sequence++) { "Discontinuous Ogg pages" }
            val count = data[offset + 26].toInt() and 255
            val tableEnd = offset + 27 + count
            require(tableEnd <= data.size) { "Truncated Ogg segment table" }
            val lengths = (offset + 27 until tableEnd).map { data[it].toInt() and 255 }
            val end = tableEnd + lengths.sum()
            require(end <= data.size) { "Truncated Ogg packet" }
            verifyChecksum(data, offset, end)
            audioPackets += readPackets(data, lengths, tableEnd, pending, packets)
            val granule = little(data, offset + 6, 8)
            if (granule != -1L) {
                require(granule >= lastGranule) { "Decreasing Opus granule position" }
                lastGranule = granule
            }
            ended = flags and 4 != 0
            offset = end
        }
        require(ended && pending.size() == 0 && packets.size == 2 && audioPackets > 0) { "Incomplete Ogg stream" }
        val preSkip = preSkip(packets)
        require(lastGranule <= maximumMs * 48 + preSkip) { "Opus granule position exceeds the media envelope" }
        val duration = (lastGranule - preSkip) * 1000 / 48000
        require(duration in minimumMs..maximumMs) { "Audio must be $minimumMs-$maximumMs milliseconds" }
        return duration
    }

    private fun preSkip(packets: List<ByteArray>): Long {
        val head = packets[0]
        require(head.size == 19 && String(head, 0, 8, Charsets.US_ASCII) == "OpusHead" &&
            head[8] == 1.toByte() && head[9] == 1.toByte() && head[18] == 0.toByte()) { "Only standard mono Opus is supported" }
        require(packets[1].size >= 8 && String(packets[1], 0, 8, Charsets.US_ASCII) == "OpusTags") { "Missing Opus tags" }
        return little(head, 10, 2)
    }

    private fun verifyChecksum(data: ByteArray, offset: Int, end: Int) {
        var crc = 0
        for (index in offset until end) {
            val byte = if (index in offset + 22 until offset + 26) 0 else data[index].toInt() and 255
            crc = (crc shl 8) xor crcTable[((crc ushr 24) xor byte) and 255]
        }
        require((crc.toLong() and 0xffffffffL) == little(data, offset + 22, 4)) { "Ogg page checksum mismatch" }
    }

    private fun readPackets(
        data: ByteArray, lengths: List<Int>, tableEnd: Int, pending: ByteArrayOutputStream, packets: MutableList<ByteArray>,
    ): Int {
        var cursor = tableEnd
        var audioPackets = 0
        lengths.forEach { length ->
            pending.write(data, cursor, length)
            cursor += length
            if (length < 255) {
                if (packets.size < 2) packets += pending.toByteArray() else {
                    require(pending.size() > 0) { "Empty Opus audio packet" }
                    audioPackets++
                }
                pending.reset()
            }
        }
        return audioPackets
    }

    private fun little(bytes: ByteArray, offset: Int, count: Int): Long =
        (0 until count).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 255) shl (8 * i)) }
}
