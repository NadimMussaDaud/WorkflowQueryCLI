public class Monitor implements Runnable {

    private String repo ;
    private String lastseen ;
    private String token ;
    private boolean running = true;
    public Monitor (String repo, String timestamp, String token ) {
        this.repo = repo;
        this.lastseen = timestamp;
        this.token = token;
    }

    @Override
    public void run() {
        running = false;
    }

    public void stop() {
        while (running) {
            try {
                // 1. Call GitHub API (list runs/jobs/steps since lastSeen)
                // 2. Diff with previous state, emit events to sink
                // 3. Update lastSeen
                // 4. Sleep a bit
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // if we ever use interrupt, we can also break here
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
