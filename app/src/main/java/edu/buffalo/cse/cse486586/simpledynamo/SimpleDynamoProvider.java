package edu.buffalo.cse.cse486586.simpledynamo;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.util.Log;

public class SimpleDynamoProvider extends ContentProvider {

	static final String TAG = SimpleDynamoProvider.class.getSimpleName();
	String selfNodeId = null;           // e.g. 5558
	String selfPort = null;             // e.g. 11118
	String selfNodeHash = null;         // e.g. jkdhfkjshdfiu (for 5558)
	String firstLeftNodeHash = null;
	String secondLeftNodeHash = null;
	static final int SERVER_PORT = 10000;
	static ArrayList<String> nodeIdOrder = new ArrayList<String>() {{
		add("5554");
		add("5556");
		add("5558");
		add("5560");
		add("5562");
	}};
	static ArrayList<String> nodeHashOrder = new ArrayList<String>();
	static Map<String, Integer> nodeHashPortMap = new HashMap<String, Integer>();
	static Map<String, String> selfFileData = new HashMap<String, String>();
	static Map<String, String> firstLeftFileData = new HashMap<String, String>();
	static Map<String, String> secondLeftFileData = new HashMap<String, String>();
	static Map<String, String> receivedFileData = new HashMap<String, String>();

	static Map<String, String> synSelfFileData = Collections.synchronizedMap(selfFileData);
	static Map<String, String> synFirstLeftFileData = Collections.synchronizedMap(firstLeftFileData);
	static Map<String, String> synSecondLeftFileData = Collections.synchronizedMap(secondLeftFileData);
	static Map<String, String> synReceivedFileData = Collections.synchronizedMap(receivedFileData);
	boolean flag = false;
	boolean insertFlag = false;
	boolean recoveryPhase = false;

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		String msg = "3";
		new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msg, selection);
		return 0;
	}

	@Override
	public String getType(Uri uri) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public synchronized Uri insert(Uri uri, ContentValues values) {
		ArrayList<String> retrievedValues = new ArrayList<String>();
		Set<Map.Entry<String, Object>> entries = values.valueSet();
		for(Map.Entry<String, Object> entry: entries) {
			retrievedValues.add(entry.getValue().toString());
		}
		Log.d("insert", "value: " + retrievedValues.get(0) + ", key: " + retrievedValues.get(1));
		Log.v("insert", values.toString());

		String key = retrievedValues.get(1);
		String value = retrievedValues.get(0);

		String msgId = "1";
		String keyHash = null;
		try {
			keyHash = genHash(key);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msgId, key, keyHash, value);
//		insertFlag = true;
//		while(insertFlag);

		return uri;
	}

	public void leftNodeHashes() {
		int i = nodeHashOrder.indexOf(selfNodeHash);
		int firstLeftIndex = i == 0 ? 4 : i-1;
		int secondLeftIndex = firstLeftIndex == 0 ? 4 : firstLeftIndex - 1;
		firstLeftNodeHash = nodeHashOrder.get(firstLeftIndex);
		secondLeftNodeHash = nodeHashOrder.get(secondLeftIndex);
	}

	public void isRecoveryPhase() {
		Context context = getContext();
		String fileName = "recovery.txt";
		try {
			Log.d(TAG, "Checking if file exists to go into recovery phase");
			InputStream inputStream = getContext().openFileInput(fileName);
			if(inputStream != null) {
				Log.d(TAG, "File exists! Enterring recovery mode");
				recoveryPhase = true;
			}
		} catch (FileNotFoundException e) {
			try {
				Log.d(TAG, "File does not exist! Skipping recovery mode");
				OutputStreamWriter outputStreamWriter = new OutputStreamWriter(context.openFileOutput(fileName, Context.MODE_PRIVATE));
				outputStreamWriter.write("True");
				outputStreamWriter.close();
				recoveryPhase = false;
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}
	}

	@Override
	public boolean onCreate() {
		TelephonyManager tel = (TelephonyManager) this.getContext().getSystemService(Context.TELEPHONY_SERVICE);
		@SuppressLint("MissingPermission") String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
		Log.d("Custom", "port number base: "+portStr);

		selfNodeId = portStr;
		Log.d(TAG, "Self Node Id: "+ selfNodeId);
		selfPort = String.valueOf((Integer.parseInt(portStr) * 2));
		Log.d(TAG, "Self Node Port: "+ selfPort);

		try {
			selfNodeHash = genHash(selfNodeId);
			Log.d(TAG, "Self Node Hash: "+ selfNodeHash);
			for(String nodeId: nodeIdOrder){
				String nodeHash = genHash(nodeId);
				nodeHashOrder.add(nodeHash);
				nodeHashPortMap.put(nodeHash, Integer.parseInt(nodeId)*2);
			}
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		Collections.sort(nodeHashOrder);
		Log.d(TAG, "Node Hash Order: " + Arrays.toString(nodeHashOrder.toArray()));

		leftNodeHashes();					// Finds the first left and second left node hashes
		Log.d(TAG, "First left node hash: "+ firstLeftNodeHash);
		Log.d(TAG, "Second left node hash: "+ secondLeftNodeHash);

		isRecoveryPhase();
		if(recoveryPhase) {
			String msg = "4";
			Log.d(TAG, "Calling client task to enter into recovery mode");
			new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msg);
		}

		Log.d(TAG, "Calling server task");
		ServerSocket serverSocket = null;
		try {
			serverSocket = new ServerSocket(SERVER_PORT);
		} catch (IOException e) {
			e.printStackTrace();
		}
		new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);

		return false;
	}

	@Override
	public synchronized Cursor query(Uri uri, String[] projection, String selection,
			String[] selectionArgs, String sortOrder) {
		String[] columns = new String[]{"key", "value"};
		MatrixCursor cur = new MatrixCursor(columns);
		if(selection.equals("@")) {
			String[] retrievedValues;
			for(Map.Entry<String, String> entry: synSelfFileData.entrySet()) {
				String key = entry.getKey();
				String value = entry.getValue();
				retrievedValues = new String[] {key, value};
				cur.addRow(retrievedValues);
			}
			for(Map.Entry<String, String> entry: synFirstLeftFileData.entrySet()) {
				String key = entry.getKey();
				String value = entry.getValue();
				retrievedValues = new String[] {key, value};
				cur.addRow(retrievedValues);
			}
			for(Map.Entry<String, String> entry: synSecondLeftFileData.entrySet()) {
				String key = entry.getKey();
				String value = entry.getValue();
				retrievedValues = new String[] {key, value};
				cur.addRow(retrievedValues);
			}
		}
		else if(synSecondLeftFileData.containsKey(selection)) {
			String[] retrievedValues = new String[] {selection, synSecondLeftFileData.get(selection)};
			cur.addRow(retrievedValues);
		}
		else {
			String msg = "2";
			new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msg, selection);
			flag = true;
			while(flag);

			String[] retrievedValues;
			for(Map.Entry<String, String> entry : synReceivedFileData.entrySet()) {
				String key = entry.getKey();
				String value = entry.getValue();
				retrievedValues = new String[] {key, value};
				cur.addRow(retrievedValues);
			}
		}
		return cur;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection,
			String[] selectionArgs) {
		return 0;
	}


	public class ServerTask extends AsyncTask<ServerSocket, String, Void> {
		@Override
		protected Void doInBackground(ServerSocket... sockets) {
			Log.d(TAG, "Inside servertask");
			ServerSocket serverSocket = sockets[0];
			try {
				while(true){
					Log.d(TAG, "Inside server while loop");
					Socket socket = serverSocket.accept();
					Log.d(TAG, "Successfully accepted");
					ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
					Message objectRead = (Message) ois.readObject();

					Log.d(TAG, "Server Task Before While Loop: Recovery phase value: " + recoveryPhase);
					while(recoveryPhase);
					Log.d(TAG, "Server Task After While Loop: Recovery phase value: " + recoveryPhase);

					if(objectRead.msgType == 1) {
						if(objectRead.primaryDestinationNodeHash.equals(selfNodeHash)) {
							synSelfFileData.put(objectRead.key, objectRead.value);
						} else if(objectRead.primaryDestinationNodeHash.equals(firstLeftNodeHash)) {
							synFirstLeftFileData.put(objectRead.key, objectRead.value);
						} else if(objectRead.primaryDestinationNodeHash.equals(secondLeftNodeHash)) {
							synSecondLeftFileData.put(objectRead.key, objectRead.value);
						}
					}
//					else if(objectRead.msgType == 21) {
//						objectRead.files.putAll(synSecondLeftFileData);
//						int nextIndex = (objectRead.nextIndex + 1) % 5;
//						if(nextIndex == objectRead.senderIndex) {
//							Message completedRead = new Message(23, objectRead.files);
//							int portToSend = nodeHashPortMap.get(nodeHashOrder.get(objectRead.senderIndex));
//							Socket senderNodeSocket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), portToSend);
//							ObjectOutputStream out = new ObjectOutputStream(senderNodeSocket.getOutputStream());
//							out.writeObject(completedRead);
//						}
//						else {
//							objectRead.nextIndex = nextIndex;
//							int portToSend = nodeHashPortMap.get(nodeHashOrder.get(objectRead.nextIndex));
//							Socket nextNodeSocket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), portToSend);
//							ObjectOutputStream out = new ObjectOutputStream(nextNodeSocket.getOutputStream());
//							out.writeObject(objectRead);
//						}
//					}
					else if(objectRead.msgType == 21) {
						objectRead.files.putAll(synSelfFileData);
						objectRead.files.putAll(synFirstLeftFileData);
						objectRead.files.putAll(synSecondLeftFileData);

						ObjectOutputStream returningAllData = new ObjectOutputStream(socket.getOutputStream());
						returningAllData.writeObject(objectRead);
					}
					else if(objectRead.msgType == 22) {
						String key = objectRead.key;
						objectRead.files.put(key, synSecondLeftFileData.get(key));

						ObjectOutputStream returningKeyData = new ObjectOutputStream(socket.getOutputStream());
						returningKeyData.writeObject(objectRead);
//						Message completedRead = new Message(23, objectRead.files);
//						Socket senderNodeSocket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(objectRead.senderPort));
//						ObjectOutputStream out = new ObjectOutputStream(senderNodeSocket.getOutputStream());
//						out.writeObject(completedRead);
					}
					else if(objectRead.msgType == 24) {
						String key = objectRead.key;
						objectRead.files.put(key, synFirstLeftFileData.get(key));

						ObjectOutputStream returningKeyData = new ObjectOutputStream(socket.getOutputStream());
						returningKeyData.writeObject(objectRead);
//						Message completedRead = new Message(23, objectRead.files);
//						Socket senderNodeSocket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(objectRead.senderPort));
//						ObjectOutputStream out = new ObjectOutputStream(senderNodeSocket.getOutputStream());
//						out.writeObject(completedRead);
					}
					else if(objectRead.msgType == 25) {
						String key = objectRead.key;
						objectRead.files.put(key, synSelfFileData.get(key));

						ObjectOutputStream returningKeyData = new ObjectOutputStream(socket.getOutputStream());
						returningKeyData.writeObject(objectRead);
//						Message completedRead = new Message(23, objectRead.files);
//						Socket senderNodeSocket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(objectRead.senderPort));
//						ObjectOutputStream out = new ObjectOutputStream(senderNodeSocket.getOutputStream());
//						out.writeObject(completedRead);
					}
					else if(objectRead.msgType == 23) {
						synReceivedFileData = objectRead.files;
						flag = false;
					}
					else if(objectRead.msgType == 31) {
						if(objectRead.primaryDestinationNodeHash.equals(selfNodeHash)) {
							synSelfFileData.remove(objectRead.key);
						}
						else if(objectRead.primaryDestinationNodeHash.equals(firstLeftNodeHash)) {
							synFirstLeftFileData.remove(objectRead.key);
						}
						else if(objectRead.primaryDestinationNodeHash.equals(secondLeftNodeHash)) {
							synSecondLeftFileData.remove(objectRead.key);
						}
					}
					else if(objectRead.msgType == 41) {
						objectRead.files.putAll(synSecondLeftFileData);
						ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
						out.writeObject(objectRead);
//						out.flush();
					}
					else if(objectRead.msgType == 42) {
						objectRead.files.putAll(synFirstLeftFileData);
						ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
						out.writeObject(objectRead);
//						out.flush();
					}
					else if(objectRead.msgType == 43) {
						objectRead.files.putAll(synSelfFileData);
						ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
						out.writeObject(objectRead);
//						out.flush();
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
			return null;
		}
	}

	public class ClientTask extends AsyncTask<String, Void, Void> {
		@Override
		protected Void doInBackground(String... msgs) {
			if(msgs[0].equals("1")){
				String key = msgs[1];
				String value = msgs[3];
				String nodeHashForKey = findNodeForKey(msgs[2]);
				ArrayList<String> replicaHashes = findReplicas(nodeHashForKey);
				Message insertMessage = new Message(1, nodeHashForKey, key, value);
				for(String nodeHash: replicaHashes) {
					try {
						Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(nodeHash));
						ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
						out.writeObject(insertMessage);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
//				insertFlag = false;
			}
			else if(msgs[0].equals("2")) {
				if(msgs[1].equals("*")) {
					Map<String, String> getAllData = new HashMap<String, String>();
					Message query = new Message(21, getAllData);
					for(String nodeHash: nodeHashOrder) {
						try {
							Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(nodeHash));
							ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
							out.writeObject(query);

							ObjectInputStream inSelfData = new ObjectInputStream(socket.getInputStream());
							Message selfDataObjectRead = (Message) inSelfData.readObject();
							synReceivedFileData.putAll(selfDataObjectRead.files);	//Check if data is being appended or overwritten
						} catch (IOException e) {
							e.printStackTrace();
						} catch (ClassNotFoundException e) {
							e.printStackTrace();
						}
					}

					flag = false;

//					int selfNodeIndex = nodeHashOrder.indexOf(selfNodeHash);
//					Message query = new Message(21, selfNodeIndex, selfNodeIndex, getAllData);
//					try {
//						Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(selfPort));
//						ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
//						out.writeObject(query);
//					} catch (IOException e) {
//						e.printStackTrace();
//					}

				}
				else {
					String key = msgs[1];
					String keyHash = null;
					try {
						keyHash = genHash(key);
					} catch (NoSuchAlgorithmException e) {
						e.printStackTrace();
					}
					String nodeHashForKey = findNodeForKey(keyHash);
					ArrayList<String> replicaHashes = findReplicas(nodeHashForKey);
					Message query = new Message(22, selfPort, key);
					try {
						Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(replicaHashes.get(2)));
						ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
						out.writeObject(query);

						ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
						Message dataReceived = (Message) in.readObject();
						synReceivedFileData = dataReceived.files;
					} catch (IOException e) {
						Message firstBackupQuery = new Message(24, selfPort, key);
						try {
							Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(replicaHashes.get(1)));
							ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
							out.writeObject(firstBackupQuery);

							ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
							Message dataReceived = (Message) in.readObject();
							synReceivedFileData = dataReceived.files;
						} catch (IOException ex) {
							Message secondBackupQuery = new Message(25, selfPort, key);
							try {
								Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(replicaHashes.get(0)));
								ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
								out.writeObject(secondBackupQuery);

								ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
								Message dataReceived = (Message) in.readObject();
								synReceivedFileData = dataReceived.files;
							} catch (IOException exc) {
								exc.printStackTrace();
							} catch (ClassNotFoundException exception) {
								exception.printStackTrace();
							}
						} catch (ClassNotFoundException ex) {
							ex.printStackTrace();
						}
					} catch (ClassNotFoundException e) {
						e.printStackTrace();
					}

					flag = false;
				}
			}
			else if(msgs[0].equals("3")) {
				String key = msgs[1];
				String keyHash = null;
				try {
					keyHash = genHash(key);
				} catch (NoSuchAlgorithmException e) {
					e.printStackTrace();
				}
				String nodeHashForKey = findNodeForKey(keyHash);
				ArrayList<String> replicaHashes = findReplicas(nodeHashForKey);
				Message deleteQuery = new Message(31, nodeHashForKey, key, null);
				for(String nodeHash: replicaHashes) {
					try {
						Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(nodeHash));
						ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
						out.writeObject(deleteQuery);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			else if(msgs[0].equals("4")) {
				Log.d(TAG, "Entered recovery mode");
				ArrayList<String> replicaHashes = findReplicas(selfNodeHash);
				Log.d(TAG, "Replicas found for node: " + selfPort);
				String nodeHashForSelfData = replicaHashes.get(2);
				Log.d(TAG, "Replica for fetching self data: " + nodeHashForSelfData);
				String nodeHashForFirstLeftData = replicaHashes.get(1);
				Log.d(TAG, "Replica for fetching first left data: " + nodeHashForFirstLeftData);
				String nodeHashForSecondLeftData = firstLeftNodeHash;
				Log.d(TAG, "Replica for fetching second left data: " + nodeHashForSecondLeftData);
				String backupNodeForSecondLeftData = secondLeftNodeHash;
				try {
					// 41: Sends Second Left Data, 42: Sends First Left Data, 43: Sends self data
					// Get self data
					Message messageForSelfData = new Message(41);
					Log.d(TAG, "Some Data");
					Log.d(TAG, nodeHashPortMap.get(nodeHashForSelfData).toString());
					Log.d(TAG, "Fetching self data: Sending request to port: " + nodeHashPortMap.get(nodeHashForSelfData));
					Socket socketForSelfData = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(nodeHashForSelfData));
					ObjectOutputStream outSelfData = new ObjectOutputStream(socketForSelfData.getOutputStream());
					outSelfData.writeObject(messageForSelfData);

					ObjectInputStream inSelfData = new ObjectInputStream(socketForSelfData.getInputStream());
					Message selfDataObjectRead = (Message) inSelfData.readObject();
					synSelfFileData.putAll(selfDataObjectRead.files);
				} catch (IOException e) {
					Message backupMessageForSelfData = new Message(42);
					try {
						Socket backupSocketForSelfData = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(nodeHashForFirstLeftData));
						ObjectOutputStream backupOutSelfData = new ObjectOutputStream(backupSocketForSelfData.getOutputStream());
						backupOutSelfData.writeObject(backupMessageForSelfData);

						ObjectInputStream backupInSelfData = new ObjectInputStream(backupSocketForSelfData.getInputStream());
						Message backupSelfDataObjectRead = (Message) backupInSelfData.readObject();
						synSelfFileData.putAll(backupSelfDataObjectRead.files);
					} catch (IOException ex) {
						ex.printStackTrace();
					} catch (ClassNotFoundException ex) {
						ex.printStackTrace();
					}
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
				}

				try {
					// Get first left data
					Message messageForFirstLeftData = new Message(41);
					Log.d(TAG, "Fetching first left data: Sending request to port: " + nodeHashPortMap.get(nodeHashForFirstLeftData));
					Socket socketForFirstLeftData = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(nodeHashForFirstLeftData));
					ObjectOutputStream outFirstLeftData = new ObjectOutputStream(socketForFirstLeftData.getOutputStream());
					outFirstLeftData.writeObject(messageForFirstLeftData);

					ObjectInputStream inFirstLeftData = new ObjectInputStream(socketForFirstLeftData.getInputStream());
					Message firstLeftDataObjectRead = (Message) inFirstLeftData.readObject();
					synFirstLeftFileData.putAll(firstLeftDataObjectRead.files);
				} catch (IOException e) {
					Message backupMessageForFirstLeftData = new Message(43);
					try {
						Socket backupSocketForFirstLeftData = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(firstLeftNodeHash));
						ObjectOutputStream backupOutFirstLeftData = new ObjectOutputStream(backupSocketForFirstLeftData.getOutputStream());
						backupOutFirstLeftData.writeObject(backupMessageForFirstLeftData);

						ObjectInputStream backupInFirstLeftData = new ObjectInputStream(backupSocketForFirstLeftData.getInputStream());
						Message backupFirstLeftDataObjectRead = (Message) backupInFirstLeftData.readObject();
						synFirstLeftFileData.putAll(backupFirstLeftDataObjectRead.files);
					} catch (IOException ex) {
						ex.printStackTrace();
					} catch (ClassNotFoundException ex) {
						ex.printStackTrace();
					}
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
				}

				try {
				// Get second left data
					Message messageForSecondLeftData = new Message(42);
					Log.d(TAG, "Fetching first left data: Sending request to port: " + nodeHashPortMap.get(nodeHashForSecondLeftData));
					Socket socketForSecondLeftData = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(nodeHashForSecondLeftData));
					ObjectOutputStream outSecondLeftData = new ObjectOutputStream(socketForSecondLeftData.getOutputStream());
					outSecondLeftData.writeObject(messageForSecondLeftData);

					ObjectInputStream inSecondLeftData = new ObjectInputStream(socketForSecondLeftData.getInputStream());
					Message secondLeftDataObjectRead = (Message) inSecondLeftData.readObject();
					synSecondLeftFileData.putAll(secondLeftDataObjectRead.files);

				} catch (IOException e) {
					Message backupMessageForSecondLeftData = new Message(43);
					try {
						Socket backupSocketForSecondLeftData = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), nodeHashPortMap.get(secondLeftNodeHash));
						ObjectOutputStream backupOutSecondLeftData = new ObjectOutputStream(backupSocketForSecondLeftData.getOutputStream());
						backupOutSecondLeftData.writeObject(backupMessageForSecondLeftData);

						ObjectInputStream backupInSecondLeftData = new ObjectInputStream(backupSocketForSecondLeftData.getInputStream());
						Message backupSecondLeftDataObjectRead = (Message) backupInSecondLeftData.readObject();
						synSecondLeftFileData.putAll(backupSecondLeftDataObjectRead.files);
					} catch (IOException ex) {
						ex.printStackTrace();
					} catch (ClassNotFoundException ex) {
						ex.printStackTrace();
					}
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
				}

				recoveryPhase = false;

			}
			return null;
		}
	}

	public ArrayList<String> findReplicas(final String nodeHash) {
		ArrayList<String> replicas = new ArrayList<String>() {{
			add(nodeHash);
		}};
		int index = nodeHashOrder.indexOf(nodeHash);
		int firstReplica = (index + 1) % 5;
		int secondReplica = (firstReplica + 1) % 5;
		replicas.add(nodeHashOrder.get(firstReplica));
		replicas.add(nodeHashOrder.get(secondReplica));
		return replicas;
	}

	public String findNodeForKey(String keyHash) {
		for(int index = 0; index < nodeHashOrder.size(); index ++) {
			if(index == 0 && keyHash.compareTo(nodeHashOrder.get(index)) < 0)
				return nodeHashOrder.get(index);
			else if(index == nodeHashOrder.size()-1 && keyHash.compareTo(nodeHashOrder.get(index)) > 0)
				return nodeHashOrder.get(0);
			else if(index > 0 && keyHash.compareTo(nodeHashOrder.get(index-1)) > 0 && keyHash.compareTo(nodeHashOrder.get(index)) < 0)
				return nodeHashOrder.get(index);
		}
		return null;
	}

	private String genHash(String input) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] sha1Hash = sha1.digest(input.getBytes());
        Formatter formatter = new Formatter();
        for (byte b : sha1Hash) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }
}
