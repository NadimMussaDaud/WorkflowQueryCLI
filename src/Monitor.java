import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.Nullable;

public class Monitor implements Runnable {

    public static final String RESET = "\033[0m";
    public static final String WHITE_BOLD = "\033[1;37m";
    public static final String RED_BOLD = "\033[1;31m";
    public static final String GREEN_BOLD = "\033[1;32m";
    public static final String YELLOW_BOLD = "\033[1;33m";

    private static final String GET_WORKFLOWS_RUNS = "https://api.github.com/repos/%s/%s/actions/runs";
    private static final String GET_JOBS_RUNS = "https://api.github.com/repos/%s/%s/actions/runs/%s/jobs";
    private static final String GET_JOB_RUN = "https://api.github.com/repos/%s/%s/actions/jobs/%s";
    private static final String GET_REPO = "https://api.github.com/search/repositories?q=%s";

    //This is an executor for many Threads since we assume MANY JOBS
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Set<String> monitoredJobs = new ConcurrentSkipListSet<>();

    private final String owner;
    private final String repo ;
    private final String token ;
    private Instant timestamp;
    private boolean running = true;
    private final HttpClient httpClient;

    public Monitor (String repo, @Nullable Instant timestamp, String token) throws Exception {
        this.timestamp = timestamp;
        this.repo = repo;
        this.token = token;
        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.owner = getOwner(repo);
    }

    @Override
    public void run() {
        HttpRequest requestRuns = createRequest(String.format(GET_WORKFLOWS_RUNS, owner, repo));
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

                    if (timestamp == null || time.isAfter(timestamp)) {
                        System.out.printf("%s[WORKFLOW %s]%s (%s)%n",
                                WHITE_BOLD,
                                id,
                                RESET,
                                node.get("status").asText());
                        printJobsWithSteps(id);
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

    private void printJobsWithSteps(String id) throws IOException, InterruptedException {
        HttpRequest jobsRequest = createRequest(String.format(GET_JOBS_RUNS, owner, repo, id));
        HttpResponse<String> responseRuns = httpClient.send(
                jobsRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        JsonNode jobsRuns = mapper.readTree(responseRuns.body());
        JsonNode array = jobsRuns.get("jobs");
        for (JsonNode job : array) {
            String jobId = job.get("id").asText();
            String jobName = job.get("name").asText();

            if(monitoredJobs.add(jobId)) {
                executorService.submit(() -> {
                    try {
                        System.out.printf("JOB %s [%s%s%s] started%n", jobName, WHITE_BOLD, jobId, RESET);
                        monitorJobSteps(jobId, jobName);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        }
    }

    private void monitorJobSteps(String id, String jobName) throws IOException, InterruptedException {
        Map<String, String> stepStatuses = new ConcurrentHashMap<>();
        boolean completionStatus = false;

        while (running && !completionStatus) {
            HttpRequest jobRequest = createRequest(String.format(GET_JOB_RUN, owner, repo, id));
            HttpResponse<String> responseJobRun = httpClient.send(
                    jobRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
            JsonNode jobRun = mapper.readTree(responseJobRun.body());
            JsonNode steps = jobRun.get("steps");

            for (JsonNode step : steps) {
                String stepStatus = step.get("status").asText();
                String stepName = step.get("name").asText();

                String previousStatus = stepStatuses.put(stepName, stepStatus);

                if(!stepStatus.equals(previousStatus)) {
                    if ("completed".equals(stepStatus)) {
                        System.out.printf("Step %s %s[Job %s]%s SHA:%s Branch:%s %s → %s %s(%s)%s%n"
                                , stepName
                                , WHITE_BOLD
                                , id
                                , RESET
                                , jobRun.get("head_sha").asText()
                                , jobRun.get("head_branch").asText()
                                , step.get("started_at").asText()
                                , step.get("completed_at").asText()
                                , GREEN_BOLD
                                , step.get("conclusion").asText()
                                , RESET);
                    } else {
                        if(step.get("started_at") == null)
                            System.out.printf("Step %s %s[Job %s]%s SHA:%s Branch:%s %s(%s)%s%n" + RESET
                                    , stepName
                                    , WHITE_BOLD
                                    , id
                                    , RESET
                                    , jobRun.get("head_sha").asText()
                                    , jobRun.get("head_branch").asText()
                                    , YELLOW_BOLD
                                    , stepStatus
                                    , RESET);
                        else System.out.printf("Step %s %s[Job %s]%s SHA:%s Branch:%s %s → ... %s(%s)%s%n"
                                , stepName
                                , WHITE_BOLD
                                , id
                                , RESET
                                , jobRun.get("head_sha").asText()
                                , jobRun.get("head_branch").asText()
                                , step.get("started_at").asText()
                                , YELLOW_BOLD
                                , stepStatus
                                , RESET);
                    }
                }
            }
            completionStatus = jobRun.get("status").asText().equals("completed");
            if(completionStatus)
                customPrint(
                        jobName,
                        id,
                        jobRun.get("head_sha").asText(),
                        jobRun.get("head_branch").asText(),
                        jobRun.get("started_at").asText(),
                        jobRun.get("completed_at").asText(),
                        jobRun.get("conclusion").asText());
            //To avoid hitting API rate limits
            else
                Thread.sleep(3000);
        }


    }

    private void customPrint(String jobName,
                             String id,
                             String sha,
                             String branch,
                             String startTime,
                             String completeTime,
                             String jobConclusion) {
        switch (jobConclusion) {
            case "success" ->
                    System.out.printf("JOB %s %s[%s]%s SHA:%s Branch:%s %s → %s %s(%s)%s%n",
                            jobName, WHITE_BOLD, id, RESET, sha, branch, startTime, completeTime, GREEN_BOLD, jobConclusion, RESET);
            case "failure" ->
                    System.out.printf("JOB %s %s[%s]%s SHA:%s Branch:%s %s → %s %s(%s)%s%n",
                            jobName,WHITE_BOLD, id, RESET, sha, branch, startTime, completeTime,RED_BOLD, jobConclusion, RESET);
            default ->
                    System.out.printf("JOB %s %s[%s]%s SHA:%s Branch:%s %s → %s (%s)%n",
                            jobName,WHITE_BOLD, id, RESET, sha, branch, startTime, completeTime, jobConclusion);
        }
    }

    private HttpRequest createRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
    }

    private String getOwner(String repo) throws Exception {
        HttpResponse<String> response = httpClient.send(
                createRequest(String.format(GET_REPO, repo)),
                HttpResponse.BodyHandlers.ofString()
        );
        JsonNode res = mapper.readTree(response.body());
        JsonNode items = res.get("items");

        if (items != null && !items.isEmpty()) {
            System.out.println("This is the owner: " + items.get(0).get("owner").get("login").asText());

            return items.get(0).get("owner").get("login").asText();
        } else {
            throw new Exception("Repository not found: " + repo);
        }
    }
}
