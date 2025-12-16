import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.Nullable;

public class Monitor implements Runnable {
    //TODO: Implement colors scheme: Green for completed, Yellow for in_progress... and Red for failures
    //TODO: Try to separate Jobs, Workflow runs and Steps with some structure.

    //This is an executor for many Threads since we assume MANY JOBS
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String GET_WORKFLOWS_RUNS = "https://api.github.com/repos/%s/%s/actions/runs";
    private static final String GET_JOBS_RUNS = "https://api.github.com/repos/%s/%s/actions/runs/%s/jobs";
    private static final String GET_JOB_RUN = "https://api.github.com/repos/%s/%s/actions/jobs/%s";

    private final String owner;
    private final String repo ;
    private final String token ;
    private Instant timestamp;
    private boolean running = true;
    private final HttpClient httpClient;

    public Monitor (String repo, @Nullable Instant timestamp, String token , String owner) {
        this.timestamp = timestamp;
        this.repo = repo;
        this.token = token;
        this.owner = owner;
        httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .build();
    }

    @Override
    public void run() {
        HttpRequest.Builder requestBase =  HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET();
        HttpRequest requestRuns = requestBase
                .uri(URI.create(String.format(GET_WORKFLOWS_RUNS, owner, repo)))
                .build();

        while (running) {
            try {
                HttpResponse<String> responseRuns = httpClient.send(
                        requestRuns,
                        HttpResponse.BodyHandlers.ofString()
                );
                JsonNode workflowRuns = mapper.readTree(responseRuns.body());
                Instant workflowTime = timestamp;
                JsonNode array = workflowRuns.get("workflow_runs");
                for (JsonNode node : array) {
                    Instant time = Instant.parse(node.get("updated_at").asText());
                    String id = node.get("id").asText();

                    if (timestamp == null || time.isAfter(workflowTime)) {
                        System.out.printf("Id: %s | status: %s.%n",
                                id,
                                node.get("status").asText());
                        printJobsWithSteps(id, requestBase);
                    }
                    // Find the most recent time
                    if (workflowTime == null || time.isAfter(workflowTime)) {
                        workflowTime = time;
                    }
                }
                timestamp = workflowTime;
            } catch (InterruptedException e) {
                // if we ever use interrupt, we can also break here
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void stop() throws IOException {
        running = false;
        executorService.shutdown();
        try (FileWriter writer = new FileWriter(Main.FILENAME, true)) {
            writer.write(String.format("%s %s\n", repo, timestamp.toString()));
        }
    }

    private void printJobsWithSteps(String id, HttpRequest.Builder requestBase ) throws IOException, InterruptedException {
        HttpRequest jobsRequest = requestBase
                .uri(URI.create(String.format(GET_JOBS_RUNS, owner, repo, id)))
                .build();
        HttpResponse<String> responseRuns = httpClient.send(
                jobsRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        JsonNode jobsRuns = mapper.readTree(responseRuns.body());
        JsonNode array = jobsRuns.get("jobs");
        for (JsonNode job : array) {
            String JobId = job.get("id").asText();
            boolean completionStatus = jobsRuns.get("status").asText().equals("completed");
            String jobName = job.get("name").asText();

            if(!completionStatus) {
                executorService.submit(() -> {
                    try {
                        monitorJobSteps(JobId, jobName, requestBase);
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
            } else {
                System.out.printf("JOB %s with ID: %s has been completed with status of: '%s'%n", jobName, id, jobsRuns.get("conclusion").asText());
            }
        }
    }

    private void monitorJobSteps(String id, String jobName, HttpRequest.Builder request) throws IOException, InterruptedException {
        Set<String> completedSteps = new HashSet<>();
        boolean completionStatus = false;
        String jobConclusion = "";

        while (running && !completionStatus) {
            HttpRequest jobRequest = request
                    .uri(URI.create(String.format(GET_JOB_RUN, owner, repo, id)))
                    .build();
            HttpResponse<String> responseJobRun = httpClient.send(
                    jobRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
            JsonNode jobRun = mapper.readTree(responseJobRun.body());
            JsonNode steps = jobRun.get("steps");

            for (JsonNode step : steps) {
                String stepStatus = step.get("status").asText();
                String stepName = step.get("name").asText();
                if(!stepStatus.equals("completed") || completedSteps.add(stepName)) {
                    System.out.printf("Step %s with JobID: %s: Started At: %s| Status: %s | Conclusion: %s%n"
                            , stepName
                            , id
                            , step.get("started_at").asText()
                            , stepStatus
                            , step.get("conclusion").asText());
                } else {
                    System.out.printf("Step %s with JobID: %s: Completed At: %s| Conclusion: %s%n"
                            , stepName
                            , id
                            , step.get("completed_at").asText()
                            , step.get("conclusion").asText());
                }
            }
            completionStatus = jobRun.get("status").asText().equals("completed");
            jobConclusion = jobRun.get("conclusion").asText();
        }

        if(completionStatus && running)
            System.out.printf("JOB %s with ID: %s has been completed with status of: '%s'%n", jobName, id, jobConclusion);
    }
}
