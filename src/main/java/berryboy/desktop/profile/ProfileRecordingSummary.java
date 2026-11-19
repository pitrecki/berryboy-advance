package berryboy.desktop.profile;

import berryboy.desktop.capture.JsonReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/** Summarizes sampled stacks separately from the uninstrumented baseline runs. */
public final class ProfileRecordingSummary {
    private ProfileRecordingSummary() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ProfileRecordingSummary profile.jfr output.json");
        var cpu = new LinkedHashMap<String, Long>(); var allocations = new LinkedHashMap<String, Long>();
        try (var file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                var event = file.readEvent(); var type = event.getEventType().getName();
                if (type.equals("jdk.ExecutionSample")) {
                    var thread = event.getThread("sampledThread");
                    if (thread != null && (thread.getJavaName().equals("main") || thread.getJavaName().equals("berryboy-profile-output")))
                        cpu.merge(thread.getJavaName() + ":" + site(event.getStackTrace()), 1L, Long::sum);
                } else if (type.equals("jdk.ObjectAllocationSample")) {
                    var thread = event.getThread();
                    if (thread != null && (thread.getJavaName().equals("main") || thread.getJavaName().equals("berryboy-profile-output")))
                        allocations.merge(thread.getJavaName() + ":" + event.getClass("objectClass").getName() + ":" + site(event.getStackTrace()), event.getLong("weight"), Long::sum);
                }
            }
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("scope", "JFR sampled stacks/weighted allocation estimates; exact per-thread allocation totals are in the profile report");
        report.put("cpu_samples", sorted(cpu)); report.put("allocation_sample_weight_bytes", sorted(allocations));
        Files.writeString(Path.of(args[1]), JsonReport.encode(report) + "\n");
    }
    private static String site(RecordedStackTrace stack) {
        if (stack == null) return "unavailable";
        for (var frame : stack.getFrames()) {
            var method = frame.getMethod();
            if (method.getType().getName().startsWith("berryboy."))
                return method.getType().getName() + "." + method.getName() + ":" + frame.getLineNumber();
        }
        return "outside-project";
    }
    private static Map<String, Long> sorted(Map<String, Long> values) {
        var result = new LinkedHashMap<String, Long>();
        values.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).limit(30)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
