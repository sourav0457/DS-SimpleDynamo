package edu.buffalo.cse.cse486586.simpledynamo;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class Message implements Serializable {
	int msgType;
	String primaryDestinationNodeHash;
	String key;
	String value;
	int senderIndex;
	int nextIndex;
	String senderPort;
	Map<String, String> files = new HashMap<String, String>();

	public Message(int msgType, String primaryDestinationNodeHash, String key, String value) {
		this.msgType = msgType;
		this.primaryDestinationNodeHash = primaryDestinationNodeHash;
		this.key = key;
		this.value = value;
	}

	public Message(int msgType, int senderIndex, int nextIndex, Map<String, String> files) {
		this.msgType = msgType;
		this.senderIndex = senderIndex;
		this.nextIndex = nextIndex;
		this.files = files;
	}

	public Message(int msgType, String senderPort, String key) {
		this.msgType = msgType;
		this.senderPort = senderPort;
		this.key = key;
	}

	public Message(int msgType, Map<String, String> files) {
		this.msgType = msgType;
		this.files = files;
	}

	public Message(int msgType, String key) {
		this.msgType = msgType;
		this.key = key;
	}

	public Message(int msgType) {
		this.msgType = msgType;
	}
}
