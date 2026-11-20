package com.vyrriox.justreplay.format;

/**
 * One clientbound play packet as stored in a replay: the tick it was received on and its encoded bytes
 * (packet id followed by the body, as produced by the play protocol codec).
 *
 * @author vyrriox
 */
public record RecordedPacket(int tick, byte[] data) {
}
