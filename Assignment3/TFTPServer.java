import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TFTPServer {

  private final int TFTPPORT;
  private static final int BUFSIZE = 516;
  private static final int HEADERSIZE = 4;
  private static final int OPPOS = 1;
  private static final int RETRANSMISSION_TIME = 5000;

  private final String ROOTDIR;

  public TFTPServer(int TFTPPORT, String ROOTDIR) {
    this.TFTPPORT = TFTPPORT;
    this.ROOTDIR = ROOTDIR;
  }

  public enum ErrorCode {
    NOT_DEFINED("Not defined", 1),
    FILE_NOT_FOUND("Requested file not found", 2),
    ACCESS_VIOLATION("Access violation occured", 3),
    DISK_FULL_OR_ALLOCATION_EXCEED("Disk full or Quota exceeded", 4),
    ILLEGAL_TFTP_OPERATION("Illegal TFTP operation", 5),
    UNKNOWN_TRANSFER_ID("Unknown port number", 6),
    FILE_ALREADY_EXISTS("File already exists", 7),
    NO_SUCH_USER("No such user", 8), 
    PREMATURE_TERMINATION("Premature termination", 9)
    ;

    private final String s;
    private final int i;

    ErrorCode(String s, int i) {
      this.s = s;
      this.i = i;
    }

    public String getMessage() {
      return s;
    }

    public int getCode() {
      return i;
    }

  }

  public static final int OP_RRQ = 1;
  public static final int OP_WRQ = 2;
  public static final int OP_DAT = 3;
  public static final int OP_ACK = 4;
  public static final int OP_ERR = 5;

  private DatagramPacket getPacket;

  private int countFirstAttempt = 0;
  private int countSecondAttempt = 0;

  /**
   * Return error message corresponding to TFTP exception.
   *
   * @param socket   (socket to read from)
   * @param errorNum (error number)
   */
  private void sendERR(DatagramSocket socket, int errorNum) {
    ErrorCode errorCodeTftp = null;

    switch (errorNum) {
      case 0:
        errorCodeTftp = ErrorCode.NOT_DEFINED;
        break;
      case 1:
        errorCodeTftp = ErrorCode.FILE_NOT_FOUND;
        break;
      case 2:
        errorCodeTftp = ErrorCode.ACCESS_VIOLATION;
        break;
      case 3:
        errorCodeTftp = ErrorCode.DISK_FULL_OR_ALLOCATION_EXCEED;
        break;
      case 4:
        errorCodeTftp = ErrorCode.ILLEGAL_TFTP_OPERATION;
        break;
      case 5:
        errorCodeTftp = ErrorCode.UNKNOWN_TRANSFER_ID;
        break;
      case 6:
        errorCodeTftp = ErrorCode.FILE_ALREADY_EXISTS;
        break;
      case 7:
        errorCodeTftp = ErrorCode.NO_SUCH_USER;
        break;
      case 8: 
        errorCodeTftp = ErrorCode.PREMATURE_TERMINATION; 
        break;  
      default:
        break;
    }

    System.out.println("The error num is: " + (errorCodeTftp.getCode() - 1));
    String codeMessage = errorCodeTftp.getMessage();
    System.out.println("The message is: " + codeMessage);

    ByteBuffer dataBuffer = ByteBuffer.allocate(codeMessage.length() + OP_ERR);
    dataBuffer.putShort((short) OP_ERR);
    dataBuffer.putShort((short) errorNum);
    dataBuffer.put(codeMessage.getBytes());
    DatagramPacket sendError = new DatagramPacket(dataBuffer.array(), dataBuffer.array().length);

    try {
      socket.send(sendError);
      socket.close();
    } catch (IOException e) {
      System.err.println("Sent multiple error codes, user might have not received them!");
    }

  }

  private void start() throws SocketException {
    byte[] buf = new byte[BUFSIZE];

    // Create socket
    DatagramSocket socket = new DatagramSocket(null);

    // Create local bind point
    SocketAddress localBindPoint = new InetSocketAddress(TFTPPORT);
    socket.bind(localBindPoint);

    System.out.printf("Listening at port %d: \n", TFTPPORT);

    // Loop to handle client requests
    while (true) {

      final InetSocketAddress clientAddress = receiveFrom(socket, buf);

      // If clientAddress is null, an error occurred in receiveFrom()
      if (clientAddress == null)
        continue;

      final StringBuffer requestedFile = new StringBuffer();
      final int reqType = parseRQ(buf, requestedFile);

      new Thread() {

        public void run() {

          try {
            DatagramSocket sendSocket = new DatagramSocket(0);

            // Connect to client
            sendSocket.connect(clientAddress);

            System.out.printf("%s request for %s from %s using port %d\n",
                (reqType == OP_RRQ) ? "Read" : "Write", requestedFile,
                clientAddress.getHostName(), clientAddress.getPort());

            switch (reqType) {
              case OP_RRQ:
                requestedFile.insert(0, ROOTDIR + "/");
                handleRQ(sendSocket, requestedFile.toString(), OP_RRQ);
                break;
              case OP_WRQ:
                requestedFile.insert(0, ROOTDIR + "/");
                handleRQ(sendSocket, requestedFile.toString(), OP_WRQ);
                break;
              default:
                sendERR(sendSocket, TFTPServer.ErrorCode.ILLEGAL_TFTP_OPERATION.getCode() - 1);
                break;
            }
            
            sendSocket.close();

          } catch (SocketException e) {
            System.err.println("Closing socket connection...");
            e.printStackTrace();
          } 
        }
      }.start();
    }
  }

  /**
   * Reads the first block of data, i.e., the request for an action (read or
   * write).
   *
   * @param socket (socket to read from)
   * @param buf    (where to store the read data)
   * @return socketAddress (the socket address of the client)
   */
  private InetSocketAddress receiveFrom(DatagramSocket socket, byte[] buf) {
    DatagramPacket data = new DatagramPacket(buf, buf.length);

    try {
      socket.receive(data);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return new InetSocketAddress(data.getAddress(), data.getPort());
  }

  /**
   * Parses the request in buf to retrieve the type of request and requestedFile.
   *
   * @param buf           (received request)
   * @param requestedFile (name of file to read/write)
   * @return opcode (request type: RRQ or WRQ)
   */
  private int parseRQ(byte[] buf, StringBuffer requestedFile) {
    char[] characterBuf = new String(buf).toCharArray();

    for (int i = 2; characterBuf[i] != '\u0000'; i++) {
      requestedFile.append(characterBuf[i]);
    }

    return Short.valueOf(buf[0]) * 255 + Short.valueOf(buf[1]);
  }

  // References:
  // https://javapapers.com/java/java-tftp-client/
  // https://coursepress.lnu.se/kurs/computer-networks-an-introduction/tftp-server-implementation-guidelines/
  /**
   * Handles RRQ and WRQ requests.
   *
   * @param sendSocket    (socket used to send/receive packets)
   * @param requestedFile (name of file to read/write)
   * @param opcode        (RRQ or WRQ)
   */
  private void handleRQ(DatagramSocket sendSocket, String requestedFile, int opcode) {
    byte[] buffer = new byte[BUFSIZE - HEADERSIZE];

    Path path = Paths.get(requestedFile.split("\n")[0]);
    File pathFile = path.toFile();

    switch (opcode) {
      case OP_RRQ:
        if (!pathFile.exists()) {
          sendERR(sendSocket, TFTPServer.ErrorCode.FILE_NOT_FOUND.getCode() - 1);
        } else if (!pathFile.canWrite() || !pathFile.canRead()) {
          sendERR(sendSocket, TFTPServer.ErrorCode.ACCESS_VIOLATION.getCode() - 1);
        }

        try {
          FileInputStream inStream = new FileInputStream(pathFile);
          short blockCounter = OPPOS;
          int count;

          boolean readFlag = true;
          do {
            int streamLen = inStream.read(buffer);

            if (streamLen == -1) {
              streamLen = 0;
            }

            count = streamLen % 512;

            if (5 > countFirstAttempt && 5 > countSecondAttempt) {
              ByteBuffer dataBuff = ByteBuffer.allocate(BUFSIZE + HEADERSIZE + 5);
              dataBuff.putShort((short) OP_DAT);
              dataBuff.putShort(blockCounter);
              dataBuff.put(buffer);
              DatagramPacket datagramPacketForSend = new DatagramPacket(dataBuff.array(),
                  streamLen + HEADERSIZE);
              readFlag = send_DATA_receive_ACK(blockCounter++, sendSocket, datagramPacketForSend);
            }

            if (countFirstAttempt >= 5
                || countSecondAttempt >= 5
                || !readFlag
                || count != 0) {
              countFirstAttempt = 0;
              countSecondAttempt = 0;
              break;
            }

          } while (true);

          inStream.close();
          sendSocket.close();

        } catch (IOException e) {
          sendERR(sendSocket, ErrorCode.NOT_DEFINED.getCode() - 1);
        } catch (TimeoutException te) {
          System.out.println("Timeout exception here..."); 
          sendERR(sendSocket, ErrorCode.PREMATURE_TERMINATION.getCode() - 1);
        }
        break;

      case OP_WRQ:
        boolean writeFlag = true;

        try {
          short blockNum = OPPOS - 1;

          if (pathFile.exists()) {
            sendERR(sendSocket, 6);
          } else {

            FileOutputStream fileOutputStream = new FileOutputStream(pathFile);
            int count = 0;

            do {

              if (5 >= countSecondAttempt && 5 >= countFirstAttempt && writeFlag) {

                ByteBuffer bufferPutRequest;

                bufferPutRequest = ByteBuffer.allocate(4);
                bufferPutRequest.putShort((short) OP_ACK);
                bufferPutRequest.putShort((short) blockNum++);

                writeFlag = receive_DATA_send_ACK(blockNum, sendSocket, bufferPutRequest);

                if (this.getPacket == null) {
                  sendERR(sendSocket, 5);
                  break;
                } else {
                  int packSize = getPacket.getLength() - HEADERSIZE;
                  fileOutputStream.write(getPacket.getData(), OP_ACK, packSize);
                  fileOutputStream.flush();
                  count = packSize % 512;
                }
              }

              if (count > 0
                  || countFirstAttempt == 5
                  || countSecondAttempt == 5
                  || !writeFlag) {
                if (count > 0) {
                  ByteBuffer bufferPutRequest;

                  bufferPutRequest = ByteBuffer.allocate(4);
                  bufferPutRequest.putShort((short) OP_ACK);
                  bufferPutRequest.putShort((short) blockNum);

                  DatagramPacket finalAck = new DatagramPacket(bufferPutRequest.array(),
                      bufferPutRequest.array().length);
                  sendSocket.send(finalAck);
                }

                countFirstAttempt = 0;
                countSecondAttempt = 0;
                break;
              }

            } while (true);

            fileOutputStream.close();
            sendSocket.close();
          }
        } catch (IOException e) {
          sendERR(sendSocket, 0);
        } catch (TimeoutException te) {
          System.out.println("Timeout exception here..."); 
          sendERR(sendSocket, ErrorCode.PREMATURE_TERMINATION.getCode() - 1);
        }
        break;
      default: 
      System.err.println("Invalid request. Sending an error packet.");
      sendERR(sendSocket, TFTPServer.ErrorCode.NOT_DEFINED.getCode() - 1);
      return; 
    }
  }

  // References:
  // https://javapapers.com/java/java-tftp-client/
  // https://coursepress.lnu.se/kurs/computer-networks-an-introduction/tftp-server-implementation-guidelines/
  /**
   * Return boolean when receiving data and sending acknowledgement during
   * transmissions.
   *
   * @param blockCounter   (the counter for the number of datagramSocket blocks)
   * @param datagramSocket (socket for packet delivery service)
   * @param bufPut         (data buffer for the content)
   * @return (True or False)
   */
  private boolean receive_DATA_send_ACK(short blockCounter, DatagramSocket datagramSocket, ByteBuffer bufPut) throws TimeoutException {

    DatagramPacket Ack = new DatagramPacket(bufPut.array(), bufPut.array().length);
    byte[] buffer = new byte[BUFSIZE];
    DatagramPacket receivingPacket = new DatagramPacket(buffer, buffer.length);
    int sum;

    try {
      datagramSocket.send(Ack);
      datagramSocket.setSoTimeout(RETRANSMISSION_TIME);
      datagramSocket.receive(receivingPacket);

      this.getPacket = receivingPacket;
      sum = (buffer[2] << 8) | (buffer[3] & 255);

      if (blockCounter == sum) {
        System.out.println("Acknowledgment arrived from: " + sum);
      } else if (6 > countFirstAttempt) {
        countFirstAttempt++;
        return receive_DATA_send_ACK(blockCounter, datagramSocket, bufPut);
      } else {
        sendERR(datagramSocket, TFTPServer.ErrorCode.NOT_DEFINED.getCode() - 1);
        return false;
      }
      countFirstAttempt = 0;

    } catch (IOException e) {
      // System.out.println("exception");
      countSecondAttempt++;

      if (6 > countSecondAttempt) {
        System.out.println(
            "Error has occurred for " + blockCounter + " after " + countSecondAttempt + " attempts");
        return receive_DATA_send_ACK(blockCounter, datagramSocket, bufPut);
      } else {
        // System.out.println("exception else");
        datagramSocket.close();
        return false;
      }
    }
    return true;
  }

  // References:
  // https://javapapers.com/java/java-tftp-client/
  // https://coursepress.lnu.se/kurs/computer-networks-an-introduction/tftp-server-implementation-guidelines/
  /**
   * Return boolean when sending data and receiving acknowledgement during
   * transmissions.
   *
   * @param blockCounter   (the counter for the number of datagramSocket blocks)
   * @param datagramSocket (socket for packet delivery service)
   * @param packet         (the packet for sending data content)
   * @return (True or False)
   */
  private boolean send_DATA_receive_ACK(short blockCounter, DatagramSocket datagramSocket, DatagramPacket packet) throws TimeoutException {

    byte[] buffer = new byte[BUFSIZE];
    DatagramPacket receivingPacket = new DatagramPacket(buffer, buffer.length);
    int sum;

    try {
      datagramSocket.send(packet);
      datagramSocket.setSoTimeout(RETRANSMISSION_TIME);
      datagramSocket.receive(receivingPacket);
      sum = (buffer[2] << 8) | (buffer[3] & 255);

      if (blockCounter == sum) {
        System.out.println("Acknowledgement sent by: " + sum);
        countSecondAttempt = 0;
      } else if (6 > countFirstAttempt) {
        countFirstAttempt++;
        send_DATA_receive_ACK(blockCounter, datagramSocket, packet);
      } else {
        sendERR(datagramSocket, ErrorCode.NOT_DEFINED.getCode() - 1);
        return false;
      }
      countFirstAttempt = 0;

    } catch (SocketTimeoutException ste) {
      System.out.println("Socket timeout occurred..."); 
      send_DATA_receive_ACK(blockCounter, datagramSocket, packet);
    } catch (IOException e) {
      countSecondAttempt++;
      System.out.println("Second attempt now...");
      if (6 > countSecondAttempt) {
        send_DATA_receive_ACK(blockCounter, datagramSocket, packet);
      } else {
        System.out.println("Closing socket connection...");
        datagramSocket.close();
        return false;
      }
    } 
    return true;
  }

  // References:
  // Fabian Dacic and Yuyao (Josh) Duan's assignment 2 (our previous assignment)
  // --> matcher and pattern usage.
  public static void main(String[] args) {

    int portNumber;
    String directory;

    if (args.length != 2) {
      System.err.print("Incorrect number of arguments, should be 2 max.");
      System.out.println("i.e --> java TFTPServer 69 tftpserverdir");
      System.exit(0);
    }

    try {
      if (Integer.parseInt(args[0]) > 0 && Integer.parseInt(args[0]) < 65535) {
        System.out.println("Valid port number, proceeding...");
      } else {
        System.out.println("Error spotted!");
      }
    } catch (NumberFormatException nfe) {
      System.err.println("Invalid port! Defaulting to 4900");
      args[0] = "4900";
    }

    portNumber = Integer.parseInt(args[0]);

    String publicRegEx = "(?:^|\\W)tftpserverdir(?:$|\\W)";
    Pattern pattern = Pattern.compile(publicRegEx, Pattern.CASE_INSENSITIVE);
    Matcher matcher = pattern.matcher(args[1]);

    if (matcher.matches()) {
      System.out.println("Navigating to TFTP server directory...");
    } else {
      System.out.println("The root directory must be the TFTP server directory, the default path has been set!");
      args[1] = "tftpserverdir";
    }

    directory = args[1];

    TFTPServer server = new TFTPServer(portNumber, directory);

    System.out.println("-------------------------------------------------------------------------");
    System.out.println("Port number is: " + portNumber);
    System.out.println("Serving directory is: /" + directory);
    System.out.println("-------------------------------------------------------------------------");

    try {
      server.start();
    } catch (SocketException e) {
      e.printStackTrace();
    }

    System.out.println("Starting up server / connection...");

  }
}