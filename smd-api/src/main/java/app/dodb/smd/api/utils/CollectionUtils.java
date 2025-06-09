package app.dodb.smd.api.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CollectionUtils {

    public static <T> List<T> combine(List<T> list1, List<T> list2) {
        var combined = new ArrayList<>(list1);
        combined.addAll(list2);
        return combined;
    }

    public static <T> Set<T> combine(Set<T> set1, Set<T> set2) {
        var combined = new HashSet<>(set1);
        combined.addAll(set2);
        return combined;
    }
}
