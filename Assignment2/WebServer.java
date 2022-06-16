import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Webserver class that can create a thread through the usage of Runnable.
 */
public class WebServer implements Runnable {

  // Variables needed to run the app, mostly OOP however thread pool is the
  // exception to the rule.
  private final Socket socket;
  private BufferedOutputStream requestContent;
  private BufferedReader reader;
  private BufferedWriter writer;
  private PrintWriter messageHeader;
  private final String directory;
  private String request;

  /**
   * One of the TAs stated that an implementation of a pool is somewhat required.
   */
  private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 10,
      60L, TimeUnit.SECONDS, new SynchronousQueue<>());

  /**
   * Constructor for the web server.
   * 
   * @param directory The public directory that is used to navigate the files.
   * 
   * @param socket    The socket that establishes a connection.
   */
  public WebServer(String directory, Socket socket) {
    this.directory = directory;
    this.socket = socket;
  }

  /**
   * Enum class for the status codes.
   */
  public enum ResponseStatusCode {
    OK_200,
    INTERNAL_SERVER_ERROR_500,
    NOT_FOUND_404,
    REDIRECT_302
  }

  /**
   * Enables execution of the concurrent thread.
   */
  @Override
  public void run() {
    try {
      messageHeader = new PrintWriter(socket.getOutputStream(), false);
      requestContent = new BufferedOutputStream(socket.getOutputStream());
      reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

      request = reader.readLine();
      do {
        if (request == null) {
          System.out.println("Request is NULL");
        } else {
          System.out.println("The request was: " + request);
          String[] requestArgs = request.split(" ");

          // This "if" block handle "GET" request
          if (requestArgs[0].equalsIgnoreCase("GET")) {
            requestGetHandler(requestArgs[1]);

            // This "if" block handle "POST" request
          } else if (requestArgs[0].equalsIgnoreCase("POST")) {
            requestPostHandler();
          } else {
            sendResponseCode(ResponseStatusCode.INTERNAL_SERVER_ERROR_500);
          }
        }
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
      } while ((request = reader.readLine()) != null);
    } catch (SocketException se) {
      se.printStackTrace();
      System.out.println("Accessing socket error occurred.");
      Thread.currentThread().interrupt();
      return;
    } catch (IOException io) {
      io.printStackTrace();
      System.out.println("Error occurred, closing the current thread.");
    }
    try {
      socket.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Method used to handle GET requests.
   * 
   * @param requestPath The file name that is to be retrieved.
   * 
   * @throws IOException
   */
  private void requestGetHandler(String requestPath) throws IOException {
    try {
      Pattern htmlPattern = Pattern.compile("^(?=.htm)(?!.html).*");
      Matcher htmMatcher = htmlPattern.matcher(requestPath);

      if (htmMatcher.matches()) {
        requestPath = requestPath + "l";
        System.out.println("htm has been changed to html...");
      }

      if (requestPath.equalsIgnoreCase("/redirect.html")) {
        sendFile("/a/index.html", ResponseStatusCode.REDIRECT_302);
      } else {
        sendFile(requestPath, ResponseStatusCode.OK_200);
      }
    } catch (NoSuchFileException e) {
      sendResponseCode(ResponseStatusCode.NOT_FOUND_404);
    }
  }

  /**
   * Method used to handle POST requests.
   * 
   * @throws IOException
   */
  private void requestPostHandler() throws IOException {
    BufferedReader readerForPost = new BufferedReader(new InputStreamReader(socket.getInputStream(),
        StandardCharsets.ISO_8859_1));

    String fileName = "";
    int n = 0;

    do {
      request = readerForPost.readLine();

      if (request.contains("Content-Disposition")) { // Get the image's name
        fileName = request.split("Content-Disposition: form-data; name=\"file\"; filename=")[1].replace(
            "\"", "");
      }

      if (request.contains("Content-Type: image/png")) {
        readerForPost.readLine(); // Skip the empty line before reaching the real image data
        new File("." + "/public" + "/upload/" + fileName); // Create the file in the upload folder
        writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("." + "/public" +
            "/upload/" + fileName, true), StandardCharsets.ISO_8859_1));

        while (n != -1) { // Read the image
          n = readerForPost.read();
          writer.write(n);
          writer.flush();
        }
        writer.close();
        break;
      }
    } while (true);

    String fileUploadedString = "Image is found at: <a href=\"/" + fileName + "\">" + fileName + "</a>"; 
    sendResponseCodeString(ResponseStatusCode.OK_200, fileUploadedString.getBytes(), "text/html"); // reference to the image that has been uploaded.
  }

  /**
   * Method used to retrieve the files from the public directory.
   * 
   * @param item The name of the file that is to be retrieved.
   * 
   * @return The actual file.
   */
  private File getFile(String item) {
    File htmlFile = new File(directory, item);
    if (htmlFile.isDirectory()) {
      htmlFile = new File(htmlFile, "index.html");
    }
    return htmlFile;
  }

  /**
   * Method used to transmit a status code.
   * 
   * @param statusCode The status code that is to be transmitted.
   * 
   * @throws IOException In case an I/O error occurs in BufferedOutputStream
   *                     (BOS).
   */
  private void sendResponseCode(ResponseStatusCode statusCode) throws IOException {
    String status = statusCode.toString();
    byte[] output = status.getBytes();
    sendResponseCodeString(statusCode, output, "text/plain");
  }

  /**
   * Method used to send back the requested file along with status code.
   * 
   * @param item       The name of the file that is to be sent.
   * 
   * @param statusCode The status code that should be followed.
   * 
   * @throws IOException
   */
  private void sendFile(String item, ResponseStatusCode statusCode) throws IOException {
    final File file = getFile(item);
    final Path path = file.toPath();
    final String type = Files.probeContentType(path);
    final byte[] data = Files.readAllBytes(path);
    sendResponseCodeString(statusCode, data, type);
  }

  /**
   * Writes the data to the output stream with the following parameters.
   * 
   * @param statusCode The status code that is to be sent.
   * 
   * @param data       The data that is to be sent.
   * 
   * @param type       The data type that is to be sent.
   * 
   * @throws IOException In case something fails in BOS.
   */
  private void sendResponseCodeString(ResponseStatusCode statusCode, byte[] data, String type) throws IOException {
    String messageForStatusCode;

    if (statusCode.equals(ResponseStatusCode.REDIRECT_302)) {
      messageForStatusCode = "302 Redirect";
    } else if (statusCode.equals(ResponseStatusCode.OK_200)) {
      messageForStatusCode = "200 OK";
    } else if (statusCode.equals(ResponseStatusCode.NOT_FOUND_404)) {
      messageForStatusCode = "404 Not found";
    } else {
      messageForStatusCode = "500 Internal server error";
    }
    messageHeader.println("HTTP/1.1 " + messageForStatusCode);
    if (type != null) {
      messageHeader.println("content-type: " + type);
    } else {
      messageHeader.println("content-type" + "text/html");
    }
    messageHeader.println("content-length: " + data.length);
    messageHeader.println();

    messageHeader.flush();
    requestContent.write(data);
    requestContent.flush();
  }

  /**
   * The main method of the program.
   * 
   * @param args The port number (integer) and "public" directory.
   * 
   * @throws Exception
   */
  public static void main(String[] args) throws Exception {

    // Checks for the argument length and if it doesn't equal to two, exit the
    // program.
    if (args.length != 2) {
      System.out.println("Please retry the following format to initialize the server: ");
      System.out.println("'java' 'WebServerV3' 'portNumber' 'directory' ");
      System.exit(1);
    } else {
      System.out.println("Establishing a connection...");
    }

    // Check for the port number, has to be between 0 and 65535 --> no more than
    // that.
    try {
      int portAsInt = Integer.parseInt(args[0]);
      if (portAsInt < 0 || portAsInt > 65535) {
        System.out.println("Incorrect port number, default port 8080 has been assigned...");
        args[0] = "8080";
      }
    } catch (NumberFormatException e) {
      System.out.println("Port is invalid. Choose a valid one!");
      System.exit(1);
    }

    // Neat trick to check whether the root directory is public or not.
    String publicRegEx = "(?:^|\\W)public(?:$|\\W)";
    Pattern pattern = Pattern.compile(publicRegEx, Pattern.CASE_INSENSITIVE);
    Matcher matcher = pattern.matcher(args[1]);

    // Check if the input is anything other than public, if it is --> set it to
    // public.
    if (matcher.matches()) {
      System.out.println("Navigating to public directory...");
    } else {
      System.out.println("The root directory must be public, the default path has been set!");
      args[1] = "public";
    }

    // Here the input for the port is parsed to an integer and then stored in a
    // variable.
    int justInCase = Integer.parseInt(args[0]);

    try (ServerSocket socket = new ServerSocket(justInCase)) {
      System.out.println("Server started, listening on port " + justInCase);
      while (true) {
        WebServer server = new WebServer(args[1], socket.accept());
        Thread thread = new Thread(server);
        executor.execute(thread);
        System.out.println("Connection has been established >>>>>>>");
      }
    }
  }

}
