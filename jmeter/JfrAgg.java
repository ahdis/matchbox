import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Aggregates a JFR recording of a load test for jfr_compare.py, without printing the (deep) stacks.
 * Run with the JDK of the image that wrote the recording (JDK 26):
 * <pre>docker run --rm -v $PWD:/w --entrypoint java matchbox-ch-elm:1156-fhir-core-7 /w/JfrAgg.java /w/profile-X.jfr > profile-X.agg.tsv</pre>
 * Output, tab separated:
 * <ul>
 * <li>{@code THREADS name count}: execution samples per thread name (digits removed)</li>
 * <li>{@code SAMPLES total truncated}: execution samples of the request threads ({@code http-nio-*})</li>
 * <li>{@code CPU method inclusive self}: execution samples of the request threads per method</li>
 * <li>{@code APPSELF method count}: execution samples of the request threads per first frame outside of the JDK</li>
 * <li>{@code ALLOC method inclusive}: allocation sample weight (bytes) of the request threads per method</li>
 * <li>{@code ALLOCCLASS class weight}: allocation sample weight (bytes) of the request threads per class</li>
 * <li>{@code NATIVE method count}: native method samples of the request threads per first frame outside of the JDK
 * (and {@code NATIVETOP} per top frame)</li>
 * <li>{@code THREADCPU name count user system}: sum of the jdk.ThreadCPULoad values per thread name</li>
 * <li>{@code CPULOAD count jvmUser jvmSystem machineTotal}: sum of the jdk.CPULoad values</li>
 * <li>{@code EVENT type count totalMillis}: count and total duration of the other events (GC, locks, ...)</li>
 * <li>{@code MONITOR class topframe count totalMillis}: monitor enter events (above the JFR threshold)</li>
 * <li>{@code PARK class topframe count totalMillis}: thread park events (above the JFR threshold)</li>
 * </ul>
 */
public class JfrAgg {

	record Acc(long[] v) {
		Acc() {
			this(new long[2]);
		}
	}

	static final Map<String, Acc> cpu = new HashMap<>();
	static final Map<String, Long> appSelf = new HashMap<>();
	static final Map<String, Long> alloc = new HashMap<>();
	static final Map<String, Long> allocClass = new HashMap<>();
	static final Map<String, Long> threads = new HashMap<>();
	static final Map<String, long[]> events = new HashMap<>();
	static final Map<String, long[]> monitors = new HashMap<>();
	static final Map<String, long[]> parks = new HashMap<>();
	static final Map<String, Long> natives = new HashMap<>();
	static final Map<String, Long> nativeTop = new HashMap<>();
	static final Map<String, double[]> threadCpu = new HashMap<>();
	static final double[] cpuLoad = new double[4];
	static long samples, truncated;

	public static void main(String[] args) throws Exception {
		try (var rf = new RecordingFile(Path.of(args[0]))) {
			while (rf.hasMoreEvents()) {
				final RecordedEvent e = rf.readEvent();
				final String type = e.getEventType().getName();
				switch (type) {
					case "jdk.ExecutionSample" -> executionSample(e);
					case "jdk.ObjectAllocationSample" -> allocationSample(e);
					case "jdk.JavaMonitorEnter" -> lock(monitors, e, "monitorClass");
					case "jdk.ThreadPark" -> lock(parks, e, "parkedClass");
					case "jdk.NativeMethodSample" -> nativeSample(e);
					case "jdk.ThreadCPULoad" -> {
						final RecordedThread t = e.getThread("eventThread");
						final String name = t == null || t.getJavaName() == null ? "?" : t.getJavaName().replaceAll("[0-9]+", "N");
						final double[] a = threadCpu.computeIfAbsent(name, k -> new double[3]);
						a[0]++;
						a[1] += e.getFloat("user");
						a[2] += e.getFloat("system");
					}
					case "jdk.CPULoad" -> {
						cpuLoad[0]++;
						cpuLoad[1] += e.getFloat("jvmUser");
						cpuLoad[2] += e.getFloat("jvmSystem");
						cpuLoad[3] += e.getFloat("machineTotal");
					}
					default -> {
						final long[] a = events.computeIfAbsent(type, k -> new long[2]);
						a[0]++;
						a[1] += e.getDuration().toNanos();
					}
				}
			}
		}
		final var out = new StringBuilder();
		threads.forEach((k, v) -> out.append("THREADS\t").append(k).append('\t').append(v).append('\n'));
		out.append("SAMPLES\t").append(samples).append('\t').append(truncated).append('\n');
		appSelf.forEach((k, v) -> out.append("APPSELF\t").append(k).append('\t').append(v).append('\n'));
		cpu.forEach((k, v) -> out.append("CPU\t").append(k).append('\t').append(v.v[0]).append('\t').append(v.v[1]).append('\n'));
		alloc.forEach((k, v) -> out.append("ALLOC\t").append(k).append('\t').append(v).append('\n'));
		allocClass.forEach((k, v) -> out.append("ALLOCCLASS\t").append(k).append('\t').append(v).append('\n'));
		events.forEach((k, v) -> out.append("EVENT\t").append(k).append('\t').append(v[0]).append('\t').append(v[1] / 1_000_000).append('\n'));
		monitors.forEach((k, v) -> out.append("MONITOR\t").append(k).append('\t').append(v[0]).append('\t').append(v[1] / 1_000_000).append('\n'));
		parks.forEach((k, v) -> out.append("PARK\t").append(k).append('\t').append(v[0]).append('\t').append(v[1] / 1_000_000).append('\n'));
		natives.forEach((k, v) -> out.append("NATIVE\t").append(k).append('\t').append(v).append('\n'));
		nativeTop.forEach((k, v) -> out.append("NATIVETOP\t").append(k).append('\t').append(v).append('\n'));
		threadCpu.forEach((k, v) -> out.append("THREADCPU\t").append(k).append('\t').append((long) v[0]).append('\t').append(v[1]).append('\t').append(v[2]).append('\n'));
		out.append("CPULOAD\t").append((long) cpuLoad[0]).append('\t').append(cpuLoad[1]).append('\t').append(cpuLoad[2]).append('\t').append(cpuLoad[3]).append('\n');
		System.out.print(out);
	}

	static boolean isRequestThread(RecordedThread t) {
		return t != null && t.getJavaName() != null && t.getJavaName().startsWith("http-nio");
	}

	static boolean isJdk(String k) {
		return k.startsWith("java.") || k.startsWith("jdk.") || k.startsWith("sun.");
	}

	static String key(RecordedFrame f) {
		return f.getMethod().getType().getName() + "." + f.getMethod().getName();
	}

	static void executionSample(RecordedEvent e) {
		final RecordedThread t = e.getThread("sampledThread");
		final String name = t == null || t.getJavaName() == null ? "?" : t.getJavaName().replaceAll("[0-9]+", "N");
		threads.merge(name, 1L, Long::sum);
		if (!isRequestThread(t)) {
			return;
		}
		final RecordedStackTrace st = e.getStackTrace();
		if (st == null) {
			return;
		}
		samples++;
		if (st.isTruncated()) {
			truncated++;
		}
		final List<RecordedFrame> frames = st.getFrames();
		final Set<String> seen = new HashSet<>();
		boolean appFound = false;
		for (int i = 0; i < frames.size(); i++) {
			final String k = key(frames.get(i));
			final Acc a = cpu.computeIfAbsent(k, x -> new Acc());
			if (i == 0) {
				a.v[1]++;
			}
			if (seen.add(k)) {
				a.v[0]++;
			}
			if (!appFound && !isJdk(k)) {
				appFound = true;
				appSelf.merge(k, 1L, Long::sum);
			}
		}
	}

	static void nativeSample(RecordedEvent e) {
		if (!isRequestThread(e.getThread("sampledThread"))) {
			return;
		}
		final RecordedStackTrace st = e.getStackTrace();
		if (st == null || st.getFrames().isEmpty()) {
			return;
		}
		nativeTop.merge(key(st.getFrames().getFirst()), 1L, Long::sum);
		for (RecordedFrame f : st.getFrames()) {
			final String k = key(f);
			if (!isJdk(k)) {
				natives.merge(k, 1L, Long::sum);
				break;
			}
		}
	}

	static void allocationSample(RecordedEvent e) {
		if (!isRequestThread(e.getThread("eventThread"))) {
			return;
		}
		final long w = e.getLong("weight");
		allocClass.merge(e.getClass("objectClass").getName(), w, Long::sum);
		final RecordedStackTrace st = e.getStackTrace();
		if (st == null) {
			return;
		}
		final Set<String> seen = new HashSet<>();
		for (RecordedFrame f : st.getFrames()) {
			final String k = key(f);
			if (seen.add(k)) {
				alloc.merge(k, w, Long::sum);
			}
		}
	}

	static void lock(Map<String, long[]> m, RecordedEvent e, String classField) {
		final var c = e.getClass(classField);
		final RecordedStackTrace st = e.getStackTrace();
		String top = "?";
		if (st != null) {
			// the first frame outside of java.* / jdk.* / sun.*
			for (RecordedFrame f : st.getFrames()) {
				final String k = key(f);
				if (!isJdk(k)) {
					top = k;
					break;
				}
			}
		}
		final String thread = isRequestThread(e.getThread("eventThread")) ? "req" : "other";
		final long[] a = m.computeIfAbsent(thread + "\t" + (c == null ? "?" : c.getName()) + "\t" + top, k -> new long[2]);
		a[0]++;
		a[1] += e.getDuration().toNanos();
	}
}
