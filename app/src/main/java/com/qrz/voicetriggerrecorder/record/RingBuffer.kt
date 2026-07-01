package com.qrz.voicetriggerrecorder.record

class RingBuffer(private val capacityFrames: Int) {

    private val frames = ArrayDeque<ShortArray>()

    fun add(frame: ShortArray) {
        frames.addLast(frame.copyOf())
        while (frames.size > capacityFrames) {
            frames.removeFirst()
        }
    }

    fun snapshot(): List<ShortArray> = frames.map { it.copyOf() }

    fun clear() {
        frames.clear()
    }

    val size: Int get() = frames.size

    val isEmpty: Boolean get() = frames.isEmpty()
}
