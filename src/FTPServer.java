/*
 * Tony Zhu
 * 
 * Classes
 * 	FTPServer - Main class
 * 	FileManager - Copy a file into the client socket
 * 	ServerReply - Reply message
 */

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/*
 * Main FTPServer class that will be listening on a port. Only
 * a single client can be connected at a time.
 */
public class FTPServer {
	
	public static int welcomePort = 9000;	// Default port the server listens on
	public static ServerSocket welcomeSocket;	// Welcome socket
	public static DataOutputStream toClient;
	
	// List of valid FTP commands
	private static final List<String> VALID_COMMANDS = new ArrayList<String>(Arrays.asList(new String[]{
		"USER",
		"PASS",
		"TYPE",
		"PORT",
		"RETR",

		"SYST",
		"NOOP",
		"QUIT"
	})); 
	
	private static boolean userSet = false;
	private static boolean loggedIn = false;
	private static boolean portSet = false;
	
	// Port number goes up until this works
	public static void createSocket(int port) {
		try {
			welcomePort = port;
			welcomeSocket = new ServerSocket(port);
		} catch (IOException e) {
			createSocket(port + 1);
		}
	}
	
	public static void main(String[] args) throws Exception {
		
		// Create the welcome socket
		if (args != null && args.length > 0) {
			// Update the welcome port number if it's passed in
			welcomePort = Integer.parseInt(args[0]);
		}
		createSocket(welcomePort);
		
		
		// Loop over client requests
		while (true) {
			
			// Wait for a client to connect
			Socket connectionSocket = welcomeSocket.accept();
			
			// Create IO streams with the connected socket
			Scanner fromClient = new Scanner(new InputStreamReader(connectionSocket.getInputStream()));
			fromClient.useDelimiter("(?<=(\r\n|\n|(\r(?!\n))))");
			toClient = new DataOutputStream(connectionSocket.getOutputStream());
			
			// Prepare for input
			ServerReply reply = new ServerReply();
			reply.setReplyByCode(220);
			sendReply(reply, toClient);
			
			// Read inputs
			while (fromClient.hasNext()) {
				reply.setErrorFlag(false);
				String command = fromClient.next();
				System.out.print(command);
				
				// Split the input into tokens separated by spaces
				String[] splitted = command.split("(?<=\\s)|(\\s)");
				String parameter = buildParameter(splitted);
				
				// Validate the command token
				String ftpCommand = splitted[0].toUpperCase();
				if (!VALID_COMMANDS.contains(ftpCommand)) {
					if (ftpCommand.length() == 3 || ftpCommand.length() == 4) {
						reply.setReplyByCode(502);
					} else {
						reply.setReplyByCode(500);
					}
					sendReply(reply, toClient);
					continue;
				}
				
				// Go to the respective handler function
				if (ftpCommand.equals(VALID_COMMANDS.get(0))) {			// USER
					parseUSER(command, parameter, reply);
				} else if (ftpCommand.equals(VALID_COMMANDS.get(1))) {	// PASS
					parsePASS(command, parameter, reply);
				} else if (ftpCommand.equals(VALID_COMMANDS.get(2))) {	// TYPE
					parseTYPE(command, parameter, reply);
				} else if (ftpCommand.equals(VALID_COMMANDS.get(3))) {	// PORT
					parsePORT(command, parameter, reply);
				} else if (ftpCommand.equals(VALID_COMMANDS.get(4))) {	// RETR
					parseRETR(command, parameter, reply);
				} else if (ftpCommand.equals(VALID_COMMANDS.get(5))) {	// SYST
					parseSYST(command, parameter, reply);
				} else if (ftpCommand.equals(VALID_COMMANDS.get(6))) {	// NOOP
					parseNOOP(command, parameter, reply);
				} else if (ftpCommand.equals(VALID_COMMANDS.get(7))) {	// QUIT
					parseQUIT(command, parameter, reply);
					sendReply(reply, toClient);
					break;
				}
				
				// If there is an error, print it
				if (reply.getErrorFlag()) {
					sendReply(reply, toClient);
					continue;
				}
				
				// Check line endings
				if (!checkLineEnd(command, splitted, reply)) {
					System.out.println("bad line ending");
					sendReply(reply, toClient);
					continue;
				}
				
				// No errors
				sendReply(reply, toClient);
			}
			
			// Cleanup
			toClient.close();
			fromClient.close();
			connectionSocket.close();
			resetState();
		}
	}
	
	/*
	 * Handler functions are split up by parsing and then processing. The parsing checks
	 * for errors in the command syntax, and the processing performs the action.
	 */
	
	public static boolean parseUSER(String command, String username, ServerReply reply) {
		if (!checkParam(command, username, reply)) return false;
		if (!checkAscii(username, reply)) return false;
		return processUSER(username, reply);
	}
	
	public static boolean processUSER(String username, ServerReply reply) {
		// Set the user if it hasn't been defined
		if (userSet) {
			reply.setReplyByCode(503);
			return false;
		} else {
			userSet = true;
			reply.setReplyByCode(331);
			return true;
		}
	}
	
	public static boolean parsePASS(String command, String password, ServerReply reply) {
		if (!checkParam(command, password, reply));
		if (!checkAscii(password, reply)) return false;
		return processPASS(password, reply);
	}
	
	public static boolean processPASS(String password, ServerReply reply) {
		if (!userSet) {
			reply.setReplyByCode(503);
			return false;
		}
		if (loggedIn) {
			reply.setReplyByCode(503);
			return false;
		}
		loggedIn = true;
		reply.setReplyByCode(230);
		return true;
	}
	
	public static boolean parseTYPE(String command, String type, ServerReply reply) {
		if (!checkParam(command, type, reply)) return false;
		if (!type.equals("A") && !type.equals("I")) {
			reply.setReplyByCode(501);
			return false;
		}
		return processTYPE(type, reply);
	}
	
	public static boolean processTYPE(String type, ServerReply reply) {
		if (!checkAuthentication(reply)) return false;
		reply.set(200, "Type set to " + type);
		return true;
	}
	
	public static boolean parsePORT(String command, String portString, ServerReply reply) {
		if (!checkParam(command, portString, reply)) return false;
		String[] address = portString.split(",");
		
		// Make sure there are four numbers for IP and two for port number
		if (address.length != 6) {
			reply.setReplyByCode(501);
			return false;
		}
		
		// Make sure all numbers are less than 256
		for (String num: address) {
			try {
				if (Integer.parseInt(num) > 255) {
					reply.setReplyByCode(501);
					return false;
				}
			} catch (NumberFormatException e) {
				reply.setReplyByCode(501);
				return false;
			}
		}
		
		return processPORT(portString, reply);
	}
	
	public static boolean processPORT(String portString, ServerReply reply) {
		if (!checkAuthentication(reply)) return false;
		String[] hostAddress = buildAddress(portString.split(","));
		FileManager.hostAddress = hostAddress;
		reply.set(200, "Port command successful (" + hostAddress[0] + "," + hostAddress[1] + ")");
		portSet = true;
		return true;
	}
	
	public static boolean parseRETR(String command, String filePath, ServerReply reply) {
		if (!checkParam(command, filePath, reply)) return false;
		if (!checkAscii(filePath, reply)) return false;
		if (!portSet) {
			reply.setReplyByCode(503);
			return false;
		}
		String[] splitted = command.split("(?<=\\s)|(?=\\s)");
		if (!checkLineEnd(command, splitted, reply)) return false;
		if (!checkAuthentication(reply)) return false;
		return processRETR(filePath, reply);
	}
	
	public static boolean processRETR(String filePath, ServerReply reply) {
		if (filePath.indexOf("/") == 0 || filePath.indexOf("\\") == 0) {
			filePath = filePath.substring(1,  filePath.length());
		}
		if (FileManager.copyFile(filePath, reply)) {
			portSet = false;
			return true;
		} else {
			return false;
		}
	}
	
	public static boolean parseSYST(String command, String parameter, ServerReply reply) {
		if (!checkNoParam(command, reply)) return false;
		if (!checkAuthentication(reply)) return false;
		return processSYST(reply);
	}
	
	public static boolean processSYST(ServerReply reply) {
		reply.setReplyByCode(215);
		return true;
	}
	
	public static boolean parseNOOP(String command, String parameter, ServerReply reply) {
		if (!checkNoParam(command, reply)) return false;
		if (!checkAuthentication(reply)) return false;
		return processNOOP(reply);
	}
	
	public static boolean processNOOP(ServerReply reply) {
		reply.set(200, "Command OK");
		return true;
	}
	
	public static boolean parseQUIT(String command, String parameter, ServerReply reply) {
		if (!checkNoParam(command, reply)) return false;
		return processQUIT(reply);
	}
	
	public static boolean processQUIT(ServerReply reply) {
//		reply.set(200, "Command OK");
		reply.setReplyByCode(221);
		return true;
	}
	
	// Make user is logged in
	public static boolean checkAuthentication(ServerReply reply) {
		if (!userSet) {
			reply.setReplyByCode(530);
			return false;
		} else if (!loggedIn) {
			reply.setReplyByCode(503);
			return false;
		}
		return true;
	}
	
	// Check if a \r\n immediately follows the command
	public static boolean checkNoParam(String command, ServerReply reply) {
		if (command.indexOf("\r\n") != 4) {
			reply.setReplyByCode(501);
			return false;
		}
		return true;
	}
	
	// Check if the command ends in \r\n
	public static boolean checkLineEnd(String command, String[] splitted, ServerReply reply) {
		return command.contains("\r\n");
	}
	
	// Check if there is a space after the command
	public static boolean checkParam(String command, String parameter, ServerReply reply) {
		if (command.indexOf(" " ) != 4) {
			reply.setReplyByCode(500);
			return false;
		}
		if (parameter.equals("")) {
			reply.setReplyByCode(501);
			return false;
		}
		return true;
	}
	
	// Check if a string only contains ASCII characters
	public static boolean checkAscii(String str, ServerReply reply) {
		for (int i = 0; i < str.length(); i++) {
			if (str.charAt(i) > 127) {
				reply.setReplyByCode(501);
				return false;
			}
		}
		return true;
	}
	
	// Build a string from the input command
	public static String buildParameter(String[] splitted) {
		String result = "";
		boolean isBuilding = false;
		try {
			int fromBack = splitted[splitted.length - 3].equals("\r") ? 3: 2;
			for (int i = 2; i < splitted.length - fromBack; i++) {
				if (!splitted[i].matches("\\s")) {
					isBuilding = true;
				}
				if (isBuilding) {
					result = result + splitted[i];
				}
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			result = "";
		}
		return result;
	}
	
	// Build an IP address and the port number given a string
	public static String[] buildAddress(String[] splitted) {
		// Create the address with periods
		String address = "";
		for (int i = 0; i < 4; i++) {
			address += splitted[i];
			if (i != 3) {
				address += ".";
			}
		}
		
		// Create the port number
		int multiplier = Integer.parseInt(splitted[4]);
		int adder = Integer.parseInt(splitted[5]);
		String num = Integer.toString(multiplier * 256 + adder);
		
		String[] result = new String[] {address, num};
		return result;
	}
	
	// Send the reply message to stdout and to the client
	public static boolean sendReply(ServerReply reply, DataOutputStream toClient) {
		reply.printMessage();
		try {
			toClient.writeBytes(reply.getMessage());
		} catch (Exception e) {
			return false;
		}
		return true;
	}
	
	// After the client connection is closed, rest the state of the server
	public static boolean resetState() {
		userSet = false;
		loggedIn = false;
		portSet = false;
		return true;
	}
}

/*
 * File manager for copying files
 */
class FileManager {
	public static int copyCount = 0;
	public static String[] hostAddress;
	
	public static boolean copyFile(String filePath, ServerReply reply) {
		// Set up the socket to connect
		Socket fileSocket;
		try {
			fileSocket = new Socket(hostAddress[0], Integer.parseInt(hostAddress[1]));
		} catch (IOException e) {
			reply.setReplyByCode(425);
			return false;
		}
		
		try {
			// Write to the socket
			File file = new File(System.getProperty("user.dir"), filePath);
			FileInputStream in = new FileInputStream(file.getPath());
			reply.setReplyByCode(150);
			FTPServer.sendReply(reply,  FTPServer.toClient);
			DataOutputStream toServer = new DataOutputStream(fileSocket.getOutputStream());
			// Copy the file
			int read = 0;
			byte[] bytes = new byte[1024];
			while ((read = in.read(bytes)) != -1) {
				toServer.write(bytes);
			}
			
			// Successful
			in.close();
			toServer.close();
			reply.setReplyByCode(250);
			
		} catch (FileNotFoundException e) {
			reply.setReplyByCode(550);
			return false;
		} catch (IOException e) {
			reply.setReplyByCode(550);
			return false;
		} finally {
			// Cleanup
			try {
				fileSocket.close();
			} catch (IOException e) {
				
			}
		}
		return true;
	}
}

/*
 * Class that represents the server response
 */

class ServerReply {
	private int replyCode;
	private String replyText;
	private boolean errorFlag;
	
	public ServerReply() {
		replyCode = 0;
		replyText = "";
		errorFlag = false;
	}
	
	public boolean getErrorFlag() {
		return errorFlag;
	}
	
	public void setErrorFlag(boolean errorFlag) {
		this.errorFlag = errorFlag;
	}
	
	public ServerReply set(int replyCode, String replyText) {
		this.replyCode = replyCode;
		this.replyText = replyText;
		return this;
	}
	
	// Set the reply message based on the code
	public ServerReply setReplyByCode(int code) {
		replyCode = code;
		if (code == 150) {
			replyText = "File status okay";
		} else if (code == 215) {
			replyText = "UNIX Type: L8";
		} else if (code == 220) {
			replyText = "COMP 431 FTP server ready";
		} else if (code == 221) {
			replyText = "Goodbye";
		} else if (code == 230) {
			replyText = "Guest login OK";
		} else if (code == 250) {
			replyText = "Requested file action completed";
		} else if (code == 331) {
			replyText = "Guest access OK, send password";
		} else if (code == 425) {
			replyText = "Can not open data connection";
			errorFlag = true;
		} else if (code == 500) {
			replyText = "Syntax error, command unrecognized";
			errorFlag = true;
		} else if (code == 501) {
			replyText = "Syntax error in parameter";
			errorFlag = true;
		} else if (code == 502) {
			replyText = "Command not implemented";
			errorFlag = true;
		} else if (code == 503) {
			replyText = "Bad sequence of commands";
			errorFlag = true;
		} else if (code == 530) {
			replyText = "Not logged in";
			errorFlag = true;
		} else if (code == 550) {
			replyText = "File not found or access denied";
			errorFlag = true;
		}
		return this;
	}
	
	public String getMessage() {
		return replyCode + " " + replyText + ".\r\n";
	}
	
	public void printMessage() {
		System.out.print(getMessage());
	}
}
