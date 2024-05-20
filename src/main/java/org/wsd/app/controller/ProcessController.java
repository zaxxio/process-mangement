package org.wsd.app.controller;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;



// Everything Related to Process Management in here.

@Data
class Process implements Serializable {
    private Integer processId;
    private Date creationDate;
}

@Component
@Scope(value = "singleton")
class InMemoryLogs {
    private ConcurrentHashMap<Integer, List<String>> logs = new ConcurrentHashMap<>();

    public void put(Integer processId, String message) {
        logs.computeIfAbsent(processId, k -> new ArrayList<>()).add(message);
    }

    public List<String> get(Integer processId) {
        return logs.get(processId);
    }
}

@Log4j2
@Service
class LogEveryMoment implements Job {
    @Autowired
    private InMemoryLogs inMemoryLogs;
    private final Gson gson = new Gson();

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap dataMap = context.getJobDetail().getJobDataMap();
        final Process process = gson.fromJson((String) dataMap.get("process"), Process.class);
        String message = "Process Id : " + process.getProcessId() + ", Hello, Core Devs Ltd. " + new Date();
        inMemoryLogs.put(process.getProcessId(), message);
        log.info(message);
    }
}

@Service
@RequiredArgsConstructor
class ProcessSchedulerService {

    private final Scheduler scheduler;
    private final InMemoryLogs inMemoryLogs;
    private final Gson gson = new Gson();


    @PostConstruct
    public void onInit() {
        try {
            scheduler.start();
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }

    public void scheduleAndStartProcess(Class<? extends Job> clazz, Process process) {

        process.setCreationDate(new Date()); // current server time creation

        try {


            JobDetail jobDetail = JobBuilder.newJob(clazz)
                    .withIdentity("processJob_" + process.getProcessId(), "processGroup")
                    .usingJobData("processId", process.getProcessId())
                    .usingJobData("process", gson.toJson(process))
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("processTrigger_" + process.getProcessId(), "processGroup")
                    .startNow()
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInSeconds(1)
                            .repeatForever())
                    .build();

            scheduler.scheduleJob(jobDetail, trigger);
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }

    public void stopProcess(Integer processId) {
        try {
            JobKey jobKey = new JobKey("processJob_" + processId, "processGroup");
            scheduler.interrupt(jobKey);
            scheduler.deleteJob(jobKey);
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Process> getAllProcess() {
        try {
            return scheduler.getJobKeys(GroupMatcher.jobGroupEquals("processGroup"))
                    .stream()
                    .map(jobKey -> {
                        try {
                            JobDetail jobDetail = scheduler.getJobDetail(jobKey);
                            Process process = gson.fromJson((String) jobDetail.getJobDataMap().get("process"), Process.class);
                            return process;
                        } catch (SchedulerException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getProcessLogs(Integer processId) {
        try {
            JobKey jobKey = new JobKey("processJob_" + processId, "processGroup");
            JobDetail jobDetail = scheduler.getJobDetail(jobKey);
            Integer id = (Integer) jobDetail.getJobDataMap().get("processId");
            return inMemoryLogs.get(id);
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }
}

@RestControllerAdvice
class ControllerExceptionHandler {

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResponseEntity<?> somethingWentWrong(Exception ex) {
        return ResponseEntity.internalServerError().body("Some is broken. Message : " + ex.getMessage());
    }

}


@RestController
@Tag(name = "Schedule Process Controller")
@SecurityRequirement(name = "BEARER_TOKEN")
@RequestMapping("/api/process")
@RequiredArgsConstructor
public class ProcessController {

    private final ProcessSchedulerService processSchedulerService;

    @PostMapping("/create-process")
    public ResponseEntity<?> startProcess(@RequestBody Process process) {
        processSchedulerService.scheduleAndStartProcess(LogEveryMoment.class, process);
        return ResponseEntity.status(HttpStatus.CREATED).body(process);
    }

    @DeleteMapping("/delete-process")
    public ResponseEntity<?> stopProcess(@RequestBody Process process) {
        processSchedulerService.stopProcess(process.getProcessId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body("Process with Id : " + process.getProcessId() + " deleted.");
    }

    @GetMapping("/get-all")
    public ResponseEntity<List<Process>> getAllProcesses() {
        List<Process> jobs = processSchedulerService.getAllProcess();
        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/get-single")
    public ResponseEntity<List<String>> getProcess(@RequestParam Integer processId) {
        List<String> job = processSchedulerService.getProcessLogs(processId);
        return ResponseEntity.ok(job);
    }

}