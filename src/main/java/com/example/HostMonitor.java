package com.example;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.*;

public class HostMonitor {
    private static final Logger logger = Logger.getLogger(HostMonitor.class.getName());
    private static final String CONFIG_FILE = "config.properties";
    private static final int PING_INTERVAL_MINUTES = 10;
    private static final int REPORT_INTERVAL_HOURS = 24;
    
    private Properties config;
    private Map<String, Boolean> hostStatus = new ConcurrentHashMap<>();
    private Map<String, Integer> hostFailCount = new ConcurrentHashMap<>();
    private Map<String, Long> lastNotificationTime = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public static void main(String[] args) {
        HostMonitor monitor = new HostMonitor();
        
        try {
            monitor.loadConfig();
            monitor.setupLogging();
            monitor.initializeHosts();
            monitor.startMonitoring();
            monitor.parseArgs(args);
        } catch (Exception e) {
            logger.severe("Error starting host monitor: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void loadConfig() throws IOException {
        config = new Properties();
        try (InputStream input = Files.newInputStream(Paths.get(CONFIG_FILE))) {
            config.load(input);
            logger.info("Configuration loaded successfully");
        } catch (IOException e) {
            logger.severe("Could not load config file: " + e.getMessage());
            throw e;
        }
    }

    private void setupLogging() {
        String logFile = config.getProperty("log.file", "hostmonitor.log");
        try {
            FileHandler fileHandler = new FileHandler(logFile, true);
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);
            logger.setLevel(Level.INFO);
            logger.info("Logging initialized to: " + logFile);
        } catch (IOException e) {
            System.err.println("Could not setup logging: " + e.getMessage());
        }
    }

    private void parseArgs(String[] args) {
        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--check-now":
                        logger.info("Debug mode: Checking hosts immediately");
                        checkHosts();
                        break;
                    case "--send-report":
                        logger.info("Debug mode: Sending daily report immediately");
                        sendDailyReport();
                        break;
                    case "--simulate-down":
                        if (i + 1 < args.length) {
                            String host = args[++i];
                            logger.info("Debug mode: Simulating host down for: " + host);
                            simulateHostDown(host);
                        } else {
                            logger.warning("--simulate-down requires a hostname parameter");
                        }
                        break;
                    case "--help":
                        printHelp();
                        System.exit(0);
                        break;
                    default:
                        logger.warning("Unknown argument: " + args[i]);
                }
            }
        }
    }

    private void printHelp() {
        System.out.println("Host Monitor - Pings hosts and sends status reports");
        System.out.println("Options:");
        System.out.println("  --check-now       Check all hosts immediately");
        System.out.println("  --send-report     Send daily report immediately");
        System.out.println("  --simulate-down   Simulate a host being down");
        System.out.println("  --help            Show this help message");
    }

    private void initializeHosts() {
        String hostsStr = config.getProperty("hosts", "");
        String[] hosts = hostsStr.split(",");
        
        for (String host : hosts) {
            host = host.trim();
            if (!host.isEmpty()) {
                hostStatus.put(host, true);  // Assume all hosts are up initially
                hostFailCount.put(host, 0);
                lastNotificationTime.put(host, 0L);
                logger.info("Added host to monitor: " + host);
            }
        }
        
        if (hostStatus.isEmpty()) {
            logger.warning("No hosts configured for monitoring");
        }
    }

    private void startMonitoring() {
        // Schedule regular host checks
        scheduler.scheduleAtFixedRate(
            this::checkHosts,
            0,
            PING_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        );
        
        // Schedule daily reports
        long initialDelay = calculateInitialDelay();
        scheduler.scheduleAtFixedRate(
            this::sendDailyReport,
            initialDelay,
            REPORT_INTERVAL_HOURS,
            TimeUnit.HOURS
        );
        
        logger.info("Monitoring started. Checking hosts every " + PING_INTERVAL_MINUTES + 
                   " minutes, reporting daily at " + config.getProperty("report.time", "00:00"));
    }
    
    private long calculateInitialDelay() {
        String reportTime = config.getProperty("report.time", "00:00");
        LocalTime scheduledTime = LocalTime.parse(reportTime, DateTimeFormatter.ofPattern("HH:mm"));
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRun = now.with(LocalTime.of(scheduledTime.getHour(), scheduledTime.getMinute()));
        
        if (now.compareTo(nextRun) > 0) {
            nextRun = nextRun.plusDays(1);
        }
        
        return Duration.between(now, nextRun).toMinutes();
    }

    private boolean hostConnectionTest(String host, int port) {
        int timeout = Integer.parseInt(config.getProperty("tcp.timeout.ms", "2000"));
        int retries = Integer.parseInt(config.getProperty("tcp.retries", "3"));
        
        for (int i = 0; i < retries; i++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), timeout);
                return true; // Connection successful
            } catch (IOException e) {
                logger.fine("TCP attempt " + (i + 1) + " failed for " + host + ": " + e.getMessage());
            }
        }
        
        return false;
    }

    private void checkHosts() {
        logger.info("Checking host status via TCP...");
        
        for (String host : hostStatus.keySet()) {
            int port = Integer.parseInt(config.getProperty("tcp.port", "80")); // Default to port 80
            boolean isUp = hostConnectionTest(host, port);
            
            if (!isUp && hostStatus.get(host)) {
                hostStatus.put(host, false);
                int count = hostFailCount.get(host) + 1;
                hostFailCount.put(host, count);
                
                logger.warning("Host " + host + " is DOWN");
                long now = System.currentTimeMillis();
                long throttlePeriod = Long.parseLong(config.getProperty("alert.throttle.minutes", "30")) * 60 * 1000;
                
                if (now - lastNotificationTime.get(host) > throttlePeriod) {
                    sendCriticalAlert(host);
                    lastNotificationTime.put(host, now);
                }
            } else if (isUp && !hostStatus.get(host)) {
                hostStatus.put(host, true);
                logger.info("Host " + host + " recovered");
                sendRecoveryAlert(host);
            } else if (!isUp) {
                int count = hostFailCount.get(host) + 1;
                hostFailCount.put(host, count);
                logger.warning("Host " + host + " still DOWN. Failure count: " + count);
            }
        }
    }

    private void simulateHostDown(String host) {
        if (hostStatus.containsKey(host)) {
            hostStatus.put(host, false);
            sendCriticalAlert(host);
            logger.info("Simulated down status for host: " + host);
        } else {
            logger.warning("Cannot simulate down status: host not found: " + host);
        }
    }

    private void sendCriticalAlert(String host) {
        String subject = "CRITICAL: Host " + host + " is DOWN";
        StringBuilder body = new StringBuilder();
        body.append("Host Monitor Alert - CRITICAL\n\n");
        body.append("The following host is currently unreachable:\n");
        body.append("Host: ").append(host).append("\n");
        body.append("Time: ").append(LocalDateTime.now()).append("\n");
        body.append("Failure count: ").append(hostFailCount.get(host)).append("\n\n");
        body.append("Please check the host immediately.\n");
        
        sendEmail(subject, body.toString());
        logger.info("Sent critical alert for host: " + host);
    }

    private void sendRecoveryAlert(String host) {
        String subject = "RECOVERED: Host " + host + " is back online";
        StringBuilder body = new StringBuilder();
        body.append("Host Monitor Alert - RECOVERY\n\n");
        body.append("The following host has recovered and is now reachable:\n");
        body.append("Host: ").append(host).append("\n");
        body.append("Time: ").append(LocalDateTime.now()).append("\n");
        body.append("Total failures: ").append(hostFailCount.get(host)).append("\n");
        
        sendEmail(subject, body.toString());
        logger.info("Sent recovery alert for host: " + host);
    }

    private void sendDailyReport() {
        logger.info("Generating daily status report");
        
        String subject = "Daily Host Status Report - " + LocalDate.now();
        StringBuilder body = new StringBuilder();
        body.append("Host Monitor - Daily Status Report\n\n");
        body.append("Report Time: ").append(LocalDateTime.now()).append("\n\n");
        body.append("Host Status Summary:\n");
        
        int totalHosts = hostStatus.size();
        long hostsUp = hostStatus.values().stream().filter(Boolean::booleanValue).count();
        
        body.append("Total hosts monitored: ").append(totalHosts).append("\n");
        body.append("Hosts UP: ").append(hostsUp).append("\n");
        body.append("Hosts DOWN: ").append(totalHosts - hostsUp).append("\n\n");
        
        body.append("Detailed Status:\n");
        for (Map.Entry<String, Boolean> entry : hostStatus.entrySet()) {
            String host = entry.getKey();
            boolean isUp = entry.getValue();
            
            body.append(host).append(": ");
            if (isUp) {
                body.append("UP - Failures in last 24h: ").append(hostFailCount.get(host));
            } else {
                body.append("DOWN - Current failure count: ").append(hostFailCount.get(host));
            }
            body.append("\n");
        }
        
        body.append("\n\nThis is an automated report from Host Monitor.");
        
        sendEmail(subject, body.toString());
        
        // Reset daily failure counts after sending the report
        for (String host : hostFailCount.keySet()) {
            hostFailCount.put(host, 0);
        }
        
        logger.info("Daily status report sent successfully");
    }

    private void sendEmail(String subject, String body) {
        String apiKey = config.getProperty("sendgrid.api.key");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            logger.severe("SendGrid API key not configured");
            return;
        }
        
        String fromEmail = config.getProperty("email.from");
        String fromName = config.getProperty("email.from.name", "Host Monitor");
        String toEmails = config.getProperty("email.to");
        
        if (fromEmail == null || toEmails == null) {
            logger.severe("Email from/to addresses not configured");
            return;
        }
        
        try {
            SendGrid sg = new SendGrid(apiKey);
            Email from = new Email(fromEmail, fromName);
            Content content = new Content("text/plain", body);
            
            Mail mail = new Mail();
            mail.setFrom(from);
            mail.setSubject(subject);
            
            for (String recipient : toEmails.split(",")) {
                recipient = recipient.trim();
                if (!recipient.isEmpty()) {
                    Personalization personalization = new Personalization();
                    personalization.addTo(new Email(recipient));
                    mail.addPersonalization(personalization);
                }
            }
            
            mail.addContent(content);
            
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            
            Response response = sg.api(request);
            
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                logger.info("Email sent successfully. Status code: " + response.getStatusCode());
            } else {
                logger.warning("Failed to send email. Status code: " + response.getStatusCode() + 
                              ", Body: " + response.getBody());
            }
        } catch (Exception e) {
            logger.severe("Error sending email: " + e.getMessage());
        }
    }
}