import java.io.*;
import java.nio.file.Files;
import java.util.List;

public class main {

    static final String FILENAME = "Repositories.txt";
    static final File file = new File(FILENAME);

    public static void main(String[] args) throws IOException {
        String repoUrl = args[0];
        String token = args[1];
        String timestamp = null;

        List<String> lines = Files.readAllLines(file.toPath());
        for (String line : lines) {
            String[] info = line.split(" ");
            if(info[0].equals(repoUrl)) timestamp = info[1];
        }

        //Get past events
        if(timestamp != null) reportPastInfo(repoUrl);


    }

    private static void reportPastInfo(String repoUrl) {

    }
}