import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/*
 * Main class that handles client requests.
 */

public class FTPClient {

	public static final String CRLF = "\r\n";
	public static final String LF = "\n";

	public static int welcomePort = 3000;
	public static Socket clientSocket;
	public static Scanner fromServer;
	public static DataOutputStream toServer;
	public static ServerSocket fileSocket;

	public static boolean connectedState = false;
	public static boolean quitLoop = false;
	public static String host;
	public static int port;

	// List of valid client requests
	public static final List<String> VALID_COMMANDS = new ArrayList<String>(Arrays.asList(new String[] {
			"CONNECT",
			"GET",
			"QUIT"
	}));

	public static void main(String[] args) {

		// Initialize
		Scanner s = new Scanner(System.in);
		ClientReply reply = new ClientReply();
		
		// Initialize the port number
		try {
			FTPCommands.portNumber = Integer.parseInt(args[0]);
		} catch (Exception e) {
			FTPCommands.portNumber = 8080;
		}

		// Stall until the next input
		try {
			while (!quitLoop && s.hasNextLine()) {

				String request = s.nextLine();
				reply.errorFlag = false;

				// Echo the request
				System.out.println(request);

				// Split by groups of spaces and store into the array
				String[] splitted = splitRequest(request);
				String command;
				if (splitted.length == 0) {
					command = "NULL"; 
				} else {
					command = splitted[0].toUpperCase();
				}

				// Parse and perform the request
				boolean result = false;
				if (VALID_COMMANDS.contains(command)) {
					if (command.equals("CONNECT")) {
						result = parseConnectRequest(request, splitted, reply);
					} else if (command.equals("GET")) {
						result = parseGetRequest(request, splitted, reply);
					} else if (command.equals("QUIT")) {
						result = parseQuitRequest(request, splitted, reply);
					}

				} else {
					// Error in parsing the request command
					reply.setReplyByCode(1).printMessage();
					continue;
				}
				reply.printMessage();

				// Successful request
				if (result) {
					if (command.equals("CONNECT")) onConnect(reply);
					else if (command.equals("GET")) onGet(buildParameter(splitted), reply);
					else if (command.equals("QUIT")) {
						onQuit(reply);
						return;
					}
					FTPCommands.printMessage();
				}
			}
		} finally {
			try {
				clientSocket.close();
			} catch (IOException e) {

			}
		}
	}
	
	public static boolean processRequest(String request, ClientReply reply) throws IOException {
		System.out.print(request);
		toServer.writeBytes(request);
		try {
			Thread.sleep(50);
		} catch (Exception e) {
			return false;
		}
		
		String cmd = request.split("\\s")[0];
		int count = 0;
		int msgCount = 0;
		String response = fromServer.next();
		boolean result = parseResponse(response);
		if (cmd.equals("RETR")) msgCount = 1;
		if (cmd.equals("RETR") && !result) msgCount = 0;
		while (clientSocket.getInputStream().available() > 0 || count < msgCount) {
			count++;
			result = parseResponse(fromServer.next());
		}
		return result;
	}

	public static boolean parseResponse(String response) {
		ServerResponse replyMessage = new ServerResponse();
		String[] splitted = ParseResponse.splitRequest(response);

		// Extract the reply code and reply text
		boolean result = true;
		result = result ? ParseResponse.parseReplyCode(response, splitted, replyMessage) : false;
		result = result ? ParseResponse.parseReplyText(response, splitted, replyMessage) : false;

		// Check CRLF
		if (result && !response.contains("\r\n")) {
			replyMessage.setReplyByCode(12);
			result = false;
		}

		// No errors
		if (result) {
			replyMessage.setReplyByCode(0);
		} 
		replyMessage.printMessage();

		// Check the codes
		if (!replyMessage.errorFlag) {
			int code = replyMessage.getReplyCode();
			if (code == 500 ||
					code == 501 ||
					code == 503 ||
					code == 530 ||
					code == 550) {
				replyMessage.errorFlag = true;
			}
		}

		return !replyMessage.errorFlag;

	}

	/*
	 *  The different requests will have a parsing and then processing function.
	 *  The parsing will attempt to extract the necessary parameters that the
	 *  processing function will use to perform the request.
	 */ 

	// Format of CONNECT request: "CONNECT<SP>+<server-host><SP>+<server-port><EOL>"
	public static boolean parseConnectRequest(String request,
			String[] splitted,
			ClientReply reply) {

		// Check parameters
		if (splitted.length == 1) {
			reply.setReplyByCode(1);
			return false;
		}

		// Validate the server host
		String serverHost;
		if (splitted.length > 2) {
			serverHost = splitted[2];
		} else {
			reply.setReplyByCode(2);
			return false;
		}

		if (serverHost.charAt(0) == '.' || serverHost.charAt(serverHost.length() - 1) == '.') {
			reply.setReplyByCode(2);	// Can't start or end with a period
			return false;
		}

		String[] elements = serverHost.split("\\."); // Elements must first contain a letter, then at least one alphanumeric character
		for (String e: elements) {
			if (!e.matches("[a-zA-Z][a-zA-Z0-9]+")) {
				reply.setReplyByCode(2);
				return false;
			}
		}

		// Check if the parameters exist
		if (splitted.length < 4) {
			reply.setReplyByCode(2);
			return false;
		} else if (splitted.length < 5) {
			reply.setReplyByCode(3);
			return false;
		}

		// Calculate the server port, which must be between 0-65535
		String port = splitted[4];
		int serverPort = 0;
		try {
			serverPort = Integer.parseInt(port);
		} catch (Exception e) {
			reply.setReplyByCode(3);
			return false;
		}
		if (serverPort < 0 || serverPort > 65535) {
			reply.setReplyByCode(3);
			return false;
		}

		// Too many parameters
		if (splitted.length > 5) {
			reply.setReplyByCode(3);
			return false;
		}
		return processConnectRequest(serverHost, serverPort, reply);
	}

	public static boolean processConnectRequest(String host, int port, ClientReply reply) {
		FTPClient.host = host;
		FTPClient.port = port;

		try {
			// Checks for a successful socket before switching to that connection
			Socket testSocket = new Socket(host, port);
			if (clientSocket != null) {
				clientSocket.close();
			}
			clientSocket = testSocket;
			
			// Successful CONNECT
			System.out.println("problem");
			fromServer = new Scanner(new InputStreamReader(clientSocket.getInputStream()));
			fromServer.useDelimiter("(?<=(\r\n|\n|(\r(?!\n))))");
			toServer = new DataOutputStream(clientSocket.getOutputStream());
			
			
			// Response
			reply.setMessage("CONNECT accepted for FTP server at host " + host + " and port " + port + CRLF);
			FTPClient.connectedState = true;
			return true;
		} catch (IOException e) {
			reply.setMessage("CONNECT failed" + CRLF);
			return false;
		}
	}

	public static boolean onConnect(ClientReply reply) {
		try {
			parseResponse(fromServer.next());
			if (processRequest(FTPCommands.USER, reply) &&
					processRequest(FTPCommands.PASS, reply) &&
					processRequest(FTPCommands.SYST, reply) &&
					processRequest(FTPCommands.TYPE, reply)) {
				return true;
			} else {
				return false;
			}
		} catch (IOException e) {
			return false;
		}
	}

	// Format of GET request: "GET<SP>+<pathname><EOL>"
	public static boolean parseGetRequest(String request,
			String[] splitted,
			ClientReply reply) {

		// Check for parameters
		if (splitted.length == 1) {		// Only a GET
			reply.setReplyByCode(1);
			return false;
		} else if (splitted.length == 2) {	// No pathname
			reply.setReplyByCode(4);
			return false;
		}

		// Parse the pathname
		String pathname = buildParameter(splitted);
		if (!checkAscii(pathname)) {
			reply.setReplyByCode(4);
			return false;
		}
		return processGetRequest(pathname, reply);
	}

	public static boolean processGetRequest(String pathname, ClientReply reply) {
		// Only works if there is a connection
		if (connectedState) {
			// Successful GET
			reply.setMessage("GET accepted for " + pathname + LF);
			return true;
		} else {
			reply.setReplyByCode(0);
			return false;
		}
	}
	
	public static boolean onGet(String pathName, ClientReply reply) {
		try {
			// Create the welcome socket
			try {
				fileSocket = new ServerSocket(FTPCommands.portNumber);
			} catch (IOException e) {
				reply.setMessage("GET failed, FTP-data port not allocated." + CRLF);
				System.out.print("GET failed, FTP-data port not allocated." + CRLF);
				return false;
			}
		
			// Calculate the inverse
			int high = FTPCommands.portNumber / 256;
			int low = FTPCommands.portNumber % 256;
			String hostPort = FTPCommands.hostAddress + "," + high + "," + low;
			String port = FTPCommands.PORT.replace("%s", hostPort);
			String retr = FTPCommands.RETR.replace("%s", pathName);
			FTPCommands.portNumber++;
			if (processRequest(port, reply) &&
					processRequest(retr, reply)) {
				
				return ClientFileManager.copyFile(pathName,  reply);
			} else {
				FTPCommands.portNumber--;
				fileSocket.close();
				return false;
			}
		} catch (IOException e) {
			try {
				fileSocket.close();
			} catch (IOException e1) {
				return false;
			}
			return false;
		}
	}

	// Format of QUIT request: "QUIT<EOL>"
	public static boolean parseQuitRequest(String request,
			String[] splitted,
			ClientReply reply) {

		// There should not be anything other than "QUIT" in the request
		if (splitted.length != 1) {
			reply.setReplyByCode(1);
			return false;
		}
		return processQuitRequest(reply);
	}

	public static boolean processQuitRequest(ClientReply reply) {

		// Only works if there is a connection
		if (connectedState) {
			// Successful QUIT
			quitLoop = true;
			reply.setReplyByCode(5);
			FTPCommands.onQuit();
			return true;
		} else {
			reply.setReplyByCode(0);
			return false;
		}
	}
	
	public static boolean onQuit(ClientReply reply) {
		try {
			if (processRequest(FTPCommands.QUIT, reply)) {
				return true;
			} else {
				return false;
			}
		} catch (IOException e) {
			return false;
		}
	}

	/*
	 * Helper functions
	 */

	// When a new connection is made, certain settings should be reinitialized
	public static void resetConnection(String connection) {

	}

	// Build a parameter that may contain spaces, since the command is split on spaces
	public static String buildParameter(String[] splitted) {
		String result = "";
		boolean isBuilding = false;
		for (int i = 2; i < splitted.length; i++) {
			if (!splitted[i].matches("\\s+")) {
				isBuilding = true;
			}
			if (isBuilding) {
				result = result + splitted[i];
			}
		}
		return result;
	}

	// Split a request by spaces
	public static String[] splitRequest(String request) {
		List<String> splitter = new ArrayList<String>();
		boolean space = true;
		splitter.add(" ");

		// Iterate over string
		for (int i = 0; i < request.length(); i++) {
			String last = request.substring(i, i+1);
			splitter.get(splitter.size() - 1);

			// Continue a space chain or continue a character chain,
			// otherwise make it a new element in the array
			if (last.matches(" +") && space) {
				space = true;
				splitter.set(splitter.size() - 1, splitter.get(splitter.size() - 1) + last);
			} else if (last.matches(" +") && !space) {
				space = true;
				splitter.add(last);
			} else if (!last.matches(" +") && !space) {
				space = false;
				splitter.set(splitter.size() - 1, splitter.get(splitter.size() - 1) + last);
			} else {
				space = false;
				splitter.add(last);
			}
		}
		splitter.remove(0);
		String[] splitted = splitter.toArray(new String[splitter.size()]);
		return splitted;
	}

	// Check if a string only contains ASCII characters
	public static boolean checkAscii(String s) {
		for (int i = 0; i < s.length(); i ++) {
			char c = s.charAt(i);
			if (c > 127) {
				return false;
			}
		}
		return true;
	}
	
	// Set up the socket for transferring files
	public static boolean createSocket(int port) {
		try {
			fileSocket = new ServerSocket(port);
		} catch (IOException e) {
			return false;
		}
		return true;
	}
}

/*
 * Helper file manager class that copies a file from a socket
 */
class ClientFileManager {
	private static String directory = "retr_files";
	private static String filePrefix = "file";
	public static int fileCount = 1;
	public static String[] hostAddress;
	
	public static boolean copyFile(String filePath, ClientReply reply) {
		// Copy the file
		try {
			Socket connectionSocket = FTPClient.fileSocket.accept();
			FileOutputStream file = new FileOutputStream(directory + "/" +filePrefix + fileCount);
			
			// Copy the file
			int read = 0;
			byte[] bytes = new byte[1024];
			while ((read = connectionSocket.getInputStream().read(bytes)) != -1) {
				file.write(bytes);
			}
			file.close();
			fileCount++;
		} catch (FileNotFoundException e) {
			return false;
		} catch (IOException e) {
			return false;
		} finally {
			try {
				FTPClient.fileSocket.close();
			} catch (IOException e) {
				return false;
			}
		}
		return true;
	}
}
/*
 * The FTPCommands class generates sequences of valid FTP commands
 */

class FTPCommands {

	public static final String CRLF = "\r\n";
	public static final String USER = "USER anonymous" + CRLF;
	public static final String PASS = "PASS guest@" + CRLF;
	public static final String SYST = "SYST" + CRLF;
	public static final String TYPE = "TYPE I" + CRLF;
	public static final String PORT = "PORT %s" + CRLF;
	public static final String RETR = "RETR %s" + CRLF;
	public static final String QUIT = "QUIT" + CRLF;

	public static String message;
	public static String hostAddress;
	public static int portNumber = 8080;

	// Initialize static variables
	static {
		message = "";
		String myIP;
		InetAddress myInet;
		try {
			myInet = InetAddress.getLocalHost();
			myIP = myInet.getHostAddress();
			hostAddress = myIP.replaceAll("\\.",  ",");
		} catch (UnknownHostException e) {

		}
	}

	public static void printMessage() {
		System.out.print(message);
	}

	public static void onConnect() {
		message = USER + PASS + SYST + TYPE;
	}

	public static void onGet(String pathname) {

		// Calculate the inverse
		int high = portNumber / 256;
		int low = portNumber % 256;
		String hostPort = hostAddress + "," + high + "," + low;

		message = PORT.replace("%s", hostPort) + RETR.replace("%s", pathname);
		portNumber++;
	}

	public static void onQuit() {
		message = QUIT;
	}

}

/*
 * Help process the server response
 */

class ParseResponse {
	// Extract the reply code, which must be in the range 100-599
	public static boolean parseReplyCode(String reply, String[] splitted, ServerResponse msg) {
		// No spaces after code
		if (splitted.length < 3) {
			msg.setReplyByCode(10);
			return false;
		}
		if (reply.indexOf(" ") < 0) {
			msg.setReplyByCode(10);
			return false;
		}
		String replyCode = reply.substring(0, reply.indexOf(" "));
		try {
			int code = Integer.parseInt(replyCode);
			if (code < 100 || code >= 600) {
				msg.setReplyByCode(10);
				return false;
			}
			msg.setReplyCode(replyCode);
		} catch (Exception e) {
			msg.setReplyByCode(10);
			return false;
		}
		return true;
	}

	// Extract the reply text
	public static boolean parseReplyText(String reply, String[] splitted, ServerResponse msg) {
		String replyText = buildParameter(splitted);

		// Check if the parameter can be built
		if (replyText.equals(" ")) {
			msg.setReplyByCode(11);
			return false;
		}

		// Check that there is only one space between the code and text
		if (!splitted[2].equals(" ")) {
			msg.setReplyByCode(11);
			return false;
		}

		// Check if it only contains ASCII characters
		if (!FTPClient.checkAscii(replyText)) {
			msg.setReplyByCode(11);
			return false;
		}

		// Strip out line endings
		if (replyText.charAt(replyText.length() - 1) == '\n') {
			replyText = replyText.substring(0, replyText.length() - 1);
		}
		if (replyText.charAt(replyText.length() - 1) == '\r') {
			replyText = replyText.substring(0, replyText.length() - 1);
		}

		msg.setReplyText(replyText);

		return true;
	}

	// Build the parameter since it is split on spaces, but the parameter may contain spaces
	public static String buildParameter(String[] splitted) {
		String result = "";
		try {
			for (int i = 2; i < splitted.length - 1; i++) {
				result += splitted[i];
			}
		} catch (Exception e) {
			result = "";
		}
		return result;
	}

	// Split a request by spaces
	public static String[] splitRequest(String request) {
		List<String> splitter = new ArrayList<String>();
		boolean space = true;
		splitter.add(" ");

		// Iterate over string
		for (int i = 0; i < request.length(); i++) {
			String last = request.substring(i, i+1);
			splitter.get(splitter.size() - 1);

			// Continue a space chain or continue a character chain,
			// otherwise make it a new element in the array
			if (last.matches(" +") && space) {
				space = true;
				splitter.set(splitter.size() - 1, splitter.get(splitter.size() - 1) + last);
			} else if (last.matches(" +") && !space) {
				space = true;
				splitter.add(last);
			} else if (!last.matches(" +") && !space) {
				space = false;
				splitter.set(splitter.size() - 1, splitter.get(splitter.size() - 1) + last);
			} else {
				space = false;
				splitter.add(last);
			}
		}
		if (!splitter.get(splitter.size() - 1).contains(" ")) {
			String last = splitter.get(splitter.size() - 1);
			int index = last.indexOf("\r");
			if (index < 0) {
				index = last.indexOf("\n");
			}
			String a = last.substring(0, index);
			String b = last.substring(index, last.length());
			splitter.set(splitter.size() - 1, a);
			splitter.add(b);
		}
		String[] splitted = splitter.toArray(new String[splitter.size()]);
		return splitted;
	}
}

/*
 * The reply class is used for ease of creating a response to any
 * client requests.
 */
class ClientReply {

	private String message;
	public boolean errorFlag;

	public ClientReply() {
		this.message = "";
		this.errorFlag = false;
	}

	// Manually set a reply message
	public ClientReply setMessage(String message) {
		this.message = message;
		return this;
	}

	// Set the reply message with a code
	public ClientReply setReplyByCode(int code) {
		String reply;
		switch (code) {
		case 0: reply = "ERROR -- expecting CONNECT";
		errorFlag = true;
		break;
		case 1: reply = "ERROR -- request";
		errorFlag = true;
		break;
		case 2: reply = "ERROR -- server-host";
		errorFlag = true;
		break;
		case 3: reply = "ERROR -- server-port";
		errorFlag = true;
		break;
		case 4: reply = "ERROR -- pathname";
		errorFlag = true;
		break;
		case 5: reply = "QUIT accepted, terminating FTP client";
		break;
		default: reply = "Invalid code";
		break;
		}
		message = reply + "\n";
		return this;
	}

	public void printMessage() {
		System.out.print(message);
	}

	public void printLineMessage() {
		System.out.println(message);
	}
}

class ServerResponse {
	private String message;
	private String replyCode;
	private String replyText;
	
	public boolean errorFlag;
	
	public ServerResponse() {
		this.message = "";
		this.errorFlag = false;
	}
	
	public int getReplyCode() {
		return Integer.parseInt(replyCode);
	}
	
	public ServerResponse setReplyCode(String replyCode) {
		this.replyCode = replyCode;
		return this;
	}
	
	public ServerResponse setReplyText(String replyText) {
		this.replyText = replyText;
		return this;
	}
	
	// Manually set a reply message
	public ServerResponse setMessage(String message) {
		this.message = message;
		return this;
	}
	
	// Set the reply message with a code
	public ServerResponse setReplyByCode(int code) {
		String reply;
		switch (code) {
		// TODO: finish this stuff
			case 10: reply = "ERROR -- reply-code";
					 errorFlag = true;
					 break;
			case 11: reply = "ERROR -- reply-text";
					 errorFlag = true;
					 break;
			case 12: reply = "ERROR -- <CRLF>";
					 errorFlag = true;
					 break;
			default: reply = "FTP reply " + replyCode + " accepted. Text is :" + replyText;
					 break;
		}
		message = reply;
		return this;
	}
	
	public void printMessage() {
		System.out.println(message);
	}
}