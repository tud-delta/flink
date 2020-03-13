package org.apache.flink.runtime.causal.determinant;

public class OrderDeterminant extends Determinant {

	private byte channel;

	public OrderDeterminant(byte channel) {
		this.channel = channel;
	}

	public byte getChannel() {
		return channel;
	}

	public void setChannel(byte channel) {
		this.channel = channel;
	}
}