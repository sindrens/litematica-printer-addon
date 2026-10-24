package no.sindrens.printeraddon.test;

/** PRINTER_ADDON_ONLY=FlyTest,FetchTest runs only those tests (short test rounds). */
final class TestFilter {
    private TestFilter() {
    }

    static boolean skip(Class<?> test) {
        String only = System.getenv("PRINTER_ADDON_ONLY");
        if (only == null || only.isBlank()) {
            return false;
        }
        for (String name : only.split(",")) {
            if (name.trim().equals(test.getSimpleName())) {
                return false;
            }
        }
        return true;
    }
}
