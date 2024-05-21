package org.wsd.app.controller;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
@Component
class IPCService {

    private final File destinationFile = new File("./core.json");
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<Integer, List<String>> processMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, java.lang.Process> processes = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public synchronized void start(Process process) {
        if (processes.containsKey(process.getProcessId())) {
            throw new RuntimeException("Already running with id: " + process.getProcessId());
        }
        ProcessBuilder processBuilder = new ProcessBuilder("java", "-jar", "app.jar", process.getProcessId().toString());
        try {
            java.lang.Process p = processBuilder.start();
            processes.put(process.getProcessId(), p);
            CompletableFuture<Void> outputFuture = processOutput(p, process.getProcessId());
            CompletableFuture<Void> errorFuture = processErrorOutput(p, process.getProcessId());
            CompletableFuture.allOf(outputFuture, errorFuture).thenRun(() -> {
                try {
                    int exitCode = p.waitFor();
                    log.info("Process exited with code: {}", exitCode);
                } catch (InterruptedException e) {
                    log.error("An error occurred while waiting for the process to exit", e);
                    Thread.currentThread().interrupt();
                }
            });
        } catch (IOException e) {
            log.error("An error occurred while starting the process", e);
        }
    }

    private CompletableFuture<Void> processOutput(java.lang.Process p, int processId) {
        return CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (this) {
                        processMessages.computeIfAbsent(processId, k -> new java.util.ArrayList<>()).add(line);
                        saveProcessMessages();
                    }
                }
            } catch (IOException e) {
                log.error("An error occurred while reading the process output", e);
            }
        }, executorService);
    }

    private CompletableFuture<Void> processErrorOutput(java.lang.Process p, int processId) {
        return CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (this) {
                        processMessages.computeIfAbsent(processId, k -> new java.util.ArrayList<>()).add(line);
                        saveProcessMessages();
                    }
                }
            } catch (IOException e) {
                log.error("An error occurred while reading the process error output", e);
            }
        }, executorService);
    }

    private synchronized void saveProcessMessages() {
        try {
            if (!Files.exists(Path.of("./core.json"))) {
                Files.createFile(Path.of("./core.json"));
            }

            String json = gson.toJson(processMessages);
            FileUtils.writeStringToFile(destinationFile, json, "UTF-8");
        } catch (IOException e) {
            log.error("An error occurred while saving process messages to the file", e);
        }
    }

    public synchronized boolean kill(Process process) {
        try {
            int processId = process.getProcessId();
            if (processes.containsKey(processId)) {
                java.lang.Process p = processes.get(processId);
                if (p.isAlive()) {
                    p.destroy();
                    // Optionally, wait for the process to be terminated
                    p.waitFor();
                }
                processes.remove(processId);
                processMessages.remove(processId);
                log.info("Process with ID {} killed and removed from tracking.", processId);
                saveProcessMessages(); // Save changes to core.json
                return true;
            }
        } catch (Exception ex) {
            log.error("An error occurred while trying to kill the process", ex);
            return false;
        }
        log.warn("Process with ID {} not found.", process.getProcessId());
        return false;
    }

    public synchronized List<String> findByProcessId(int processId) {
        return processMessages.get(processId);
    }

    public synchronized ConcurrentHashMap<String, String> findAllProcesses() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        for (Map.Entry<Integer, java.lang.Process> entry : processes.entrySet()) {
            Integer key = entry.getKey();
            java.lang.Process process = entry.getValue();
            map.put("Process Id : " + key.toString(), " Start Time : " + process.info().startInstant().toString());
        }
        return map;
    }
}

@RestController
@Tag(name = "Inter Process Communication Controller")
@SecurityRequirement(name = "BEARER_TOKEN")
@RequestMapping("/api/ipc")
@RequiredArgsConstructor
public class InterProcessCommunicationController {

    @Autowired
    private IPCService ipcService;

    @Operation(summary = "Create a new process")
    @PostMapping("/create-process")
    public ResponseEntity<?> startProcess(@RequestBody Process process) {
        try {
            ipcService.start(process);
            return ResponseEntity.status(HttpStatus.CREATED).body("Process started successfully with ID: " + process.getProcessId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @Operation(summary = "Kill an existing process")
    @DeleteMapping("/delete-process/{processId}")
    public ResponseEntity<?> killProcess(@PathVariable int processId) {
        Process process = new Process();
        process.setProcessId(processId);
        boolean success = ipcService.kill(process);
        if (success) {
            return ResponseEntity.ok("Process killed successfully.");
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Process not found or could not be killed.");
        }
    }

    @Operation(summary = "Get process messages by ID")
    @GetMapping("/get-single/{processId}")
    public ResponseEntity<?> getProcessMessages(@PathVariable int processId) {
        List<String> messages = ipcService.findByProcessId(processId);
        if (messages != null) {
            return ResponseEntity.ok(messages);
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No messages found for process ID: " + processId);
        }
    }

    @Operation(summary = "Get all process messages")
    @GetMapping("/get-all")
    public ResponseEntity<?> getAllProcessMessages() {
        ConcurrentHashMap<?, ?> allMessages = ipcService.findAllProcesses();
        return ResponseEntity.ok(allMessages);
    }
}
