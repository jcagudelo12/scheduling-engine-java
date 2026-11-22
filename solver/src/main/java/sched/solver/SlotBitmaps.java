package sched.solver;

import java.util.Arrays;
import java.util.List;
import sched.domain.Section;
import sched.domain.TimeSlot;

/**
 * Representación exacta de las franjas de un conjunto de grupos como vectores de bits.
 *
 * <p>Las fronteras (día*1440 + minuto) de todas las franjas, ordenadas y sin repetir, definen
 * intervalos elementales {@code [b_i, b_i+1)}; cada uno es un bit. Un grupo tiene un bit
 * encendido por cada intervalo elemental que cubre, así que dos grupos se cruzan si y solo si
 * el AND de sus máscaras no es cero (equivalente a {@link TimeSlot#overlaps} para intervalos
 * semiabiertos).
 */
public final class SlotBitmaps {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private final long[][] masks;
    private final int words;

    private SlotBitmaps(long[][] masks, int words) {
        this.masks = masks;
        this.words = words;
    }

    public static SlotBitmaps of(List<Section> sections) {
        int count = 0;
        for (Section s : sections) {
            count += 2 * s.slots().size();
        }
        int[] raw = new int[count];
        int k = 0;
        for (Section s : sections) {
            for (TimeSlot t : s.slots()) {
                raw[k++] = absolute(t, t.start());
                raw[k++] = absolute(t, t.end());
            }
        }
        int[] bounds = Arrays.stream(raw).sorted().distinct().toArray();
        int bits = Math.max(bounds.length - 1, 0);
        int words = Math.max((bits + 63) >>> 6, 1);

        long[][] masks = new long[sections.size()][words];
        for (int i = 0; i < masks.length; i++) {
            for (TimeSlot t : sections.get(i).slots()) {
                int from = Arrays.binarySearch(bounds, absolute(t, t.start()));
                int to = Arrays.binarySearch(bounds, absolute(t, t.end()));
                for (int b = from; b < to; b++) {
                    masks[i][b >>> 6] |= 1L << b;
                }
            }
        }
        return new SlotBitmaps(masks, words);
    }

    private static int absolute(TimeSlot slot, int minute) {
        return slot.day().ordinal() * MINUTES_PER_DAY + minute;
    }

    public int size() {
        return masks.length;
    }

    /** Máscara del grupo {@code index}; no se debe modificar. */
    public long[] mask(int index) {
        return masks[index];
    }

    public boolean clash(int a, int b) {
        long[] x = masks[a];
        long[] y = masks[b];
        for (int w = 0; w < words; w++) {
            if ((x[w] & y[w]) != 0) {
                return true;
            }
        }
        return false;
    }
}
