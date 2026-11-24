import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/** Sonda de latencia independiente de k6 (bench/overflow-run.sh, PROBE=1): bucle abierto, mide desde la hora programada. Uso: java LatencyProbe.java cuerpos.json tokens.json tasa hilos inicio_ms fin_ms */
public class LatencyProbe {
  public static void main(String[] a) throws Exception {
    String bodies = Files.readString(Path.of(a[0]));           // students.json
    List<String> toks = new ArrayList<>();
    Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(Files.readString(Path.of(a[1])));
    while (m.find()) toks.add(m.group(1));
    List<String> bs = new ArrayList<>();
    // cuerpos: objetos de primer nivel del arreglo
    int depth = 0, st = -1;
    for (int i = 0; i < bodies.length(); i++) {
      char c = bodies.charAt(i);
      if (c == '{') { if (depth++ == 0) st = i; }
      else if (c == '}') { if (--depth == 0) bs.add(bodies.substring(st, i + 1)); }
    }
    double rate = Double.parseDouble(a[2]); int threads = Integer.parseInt(a[3]);
    long startAt = Long.parseLong(a[4]); long endAt = Long.parseLong(a[5]);   // ms epoch
    HttpClient cl = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    long[] sched = new long[1_000_000], sent = new long[1_000_000], when = new long[1_000_000]; AtomicInteger n = new AtomicInteger();
    long periodNs = (long) (threads / rate * 1e9);
    Thread[] ts = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      final int id = t;
      ts[t] = new Thread(() -> {
        long next = startAt * 1_000_000L + id * periodNs / threads; int k = id;
        long base = System.currentTimeMillis() * 1_000_000L - System.nanoTime();
        while (next < endAt * 1_000_000L) {
          long now = System.nanoTime() + base;
          if (next > now) { try { Thread.sleep((next - now) / 1_000_000L, (int) ((next - now) % 1_000_000L)); } catch (Exception e) {} }
          long s0 = System.nanoTime();
          int idx = (k * 7919) % bs.size();
          try {
            HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:8080/combinations"))
                .header("content-type", "application/json").header("authorization", "Bearer " + toks.get(idx % toks.size()))
                .POST(HttpRequest.BodyPublishers.ofString(bs.get(idx))).build();
            cl.send(r, HttpResponse.BodyHandlers.ofString());
          } catch (Exception e) { }
          long s1 = System.nanoTime();
          int j = n.getAndIncrement();
          if (j < sched.length) { sched[j] = s1 - (next - base); sent[j] = s1 - s0; when[j] = next / 1_000_000L; }
          next += periodNs; k += threads;
        }
      });
      ts[t].start();
    }
    for (Thread t : ts) t.join();
    int cnt = Math.min(n.get(), sched.length);
    double[] s = new double[cnt], d = new double[cnt];
    for (int i = 0; i < cnt; i++) { s[i] = sched[i] / 1e6; d[i] = sent[i] / 1e6; }
    // Serie en el tiempo: p99 y máximo (desde el envío) por cubeta de 10 s a partir de `inicio`.
    TreeMap<Long, List<Double>> buckets = new TreeMap<>();
    for (int i = 0; i < cnt; i++) buckets.computeIfAbsent((when[i] - startAt) / 10_000, k -> new ArrayList<>()).add(sent[i] / 1e6);
    StringBuilder series = new StringBuilder();
    for (var e : buckets.entrySet()) {
      List<Double> l = e.getValue(); Collections.sort(l);
      series.append(String.format("  t=%3ds n=%d p50=%.2f p99=%.1f max=%.0f%n", e.getKey() * 10, l.size(), l.get(l.size() / 2), l.get((int) (l.size() * .99)), l.get(l.size() - 1)));
    }
    Arrays.sort(s); Arrays.sort(d);
    System.out.printf("n=%d  desde_hora_programada p50=%.2f p95=%.1f p99=%.1f max=%.0f | desde_envio p50=%.2f p95=%.1f p99=%.1f max=%.0f ms%n", cnt,
        s[cnt / 2], s[(int) (cnt * .95)], s[(int) (cnt * .99)], s[cnt - 1], d[cnt / 2], d[(int) (cnt * .95)], d[(int) (cnt * .99)], d[cnt - 1]);
    System.out.print(series);
  }
}
