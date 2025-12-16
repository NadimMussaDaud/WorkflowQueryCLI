import java.io.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

public class main {

    static final String FILENAME = "Repositories.txt";
    static final File file = new File(FILENAME);

    public static void main(String[] args) throws IOException, InterruptedException {
        String[] urlParts = args[0].split("/");
        String repo = urlParts[4];
        String owner = urlParts[3];
        String token = args[1];
        String timestamp = null;

        if(!file.exists() && !file.createNewFile()) throw new RuntimeException();

        List<String> lines = Files.readAllLines(file.toPath());
        for (String line : lines) {
            String[] info = line.split(" ");
            if(info[0].equals(repo)) timestamp = info[1];
        }

        Monitor monitor = new Monitor(repo, timestamp==null ? null : Instant.parse(timestamp) , token, owner);

        Thread monitorThread = new Thread(monitor, "monitor-thread");
        monitorThread.start();

        System.out.println("Monitoring " + repo + "...");
        System.out.println("Press ENTER to stop.");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            br.readLine();
        }

        monitor.stop();
        monitorThread.join();

        System.out.println("Stopped.");

    }

}