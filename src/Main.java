import java.io.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

public class Main {
    static final String FILENAME = "Repositories.txt";
    static final File file = new File(FILENAME);

    public static void main(String[] args) throws IOException, InterruptedException {
        String repo = args[0];
        String token = args[1];
        String timestamp = null;

        System.out.println("This is the repo: " + repo);
        System.out.println("This is the token: " + token);

        if(!file.exists() && !file.createNewFile()) throw new RuntimeException();

        List<String> lines = Files.readAllLines(file.toPath());
        for (String line : lines) {
            String[] info = line.split(" ");
            if(info[0].equals(repo)) timestamp = info[1];
        }

        Monitor monitor = new Monitor(repo, timestamp==null ? null : Instant.parse(timestamp) , token, "NadimMussaDaud");

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