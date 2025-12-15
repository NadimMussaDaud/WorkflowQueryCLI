import java.io.*;
import java.nio.file.Files;
import java.util.List;

public class main {

    static final String FILENAME = "Repositories.txt";
    static final File file = new File(FILENAME);

    public static void main(String[] args) throws IOException, InterruptedException {
        String repoUrl = args[0];
        String token = args[1];
        String timestamp = null;

        List<String> lines = Files.readAllLines(file.toPath());
        for (String line : lines) {
            String[] info = line.split(" ");
            if(info[0].equals(repoUrl)) timestamp = info[1];
        }

        Monitor monitor = new Monitor(repoUrl, timestamp, token);

        Thread monitorThread = new Thread(monitor, "monitor-thread");
        monitorThread.start();

        System.out.println("Monitoring " + repoUrl + "...");
        System.out.println("Press ENTER to stop.");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            br.readLine();
        }

        monitor.stop();
        monitorThread.join();

        System.out.println("Stopped.");

    }

}