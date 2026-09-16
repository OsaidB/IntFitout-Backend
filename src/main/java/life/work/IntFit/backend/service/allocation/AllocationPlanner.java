package life.work.IntFit.backend.service.allocation;

import life.work.IntFit.backend.dto.daily_assignments.AllocationErrorDTO;
import life.work.IntFit.backend.dto.daily_assignments.CategoryAllocationDTO;
import life.work.IntFit.backend.dto.daily_assignments.MemberAllocationDTO;
import life.work.IntFit.backend.dto.daily_assignments.SiteHoursDTO;
import life.work.IntFit.backend.exception.error.AllocationValidationException;
import life.work.IntFit.backend.model.entity.WorkAssignment;
import life.work.IntFit.backend.model.enums.AllocationMode;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the allocation state a daily save will leave behind and validates it
 * as a whole, before anything is written.
 *
 * <p>Per member and category: an omitted category keeps its stored values,
 * AUTO clears them, CUSTOM replaces them. Whatever the source, a resulting
 * CUSTOM allocation must cover exactly the member's submitted worksites and sum
 * exactly to the category total (8 for regular, the submitted overtime for
 * overtime). Any failure rejects the entire day.
 */
public final class AllocationPlanner {

    public enum Category {
        REGULAR("regular hours"),
        OVERTIME("overtime");

        private final String label;

        Category(String label) {
            this.label = label;
        }
    }

    /** Custom values to persist, keyed member → worksite. Members absent from a map are AUTO. */
    public record Plan(Map<Long, Map<Long, BigDecimal>> regular,
                       Map<Long, Map<Long, BigDecimal>> overtime) {

        public BigDecimal regularFor(Long memberId, Long worksiteId) {
            return valueFor(regular, memberId, worksiteId);
        }

        public BigDecimal overtimeFor(Long memberId, Long worksiteId) {
            return valueFor(overtime, memberId, worksiteId);
        }

        private static BigDecimal valueFor(Map<Long, Map<Long, BigDecimal>> values, Long memberId, Long worksiteId) {
            Map<Long, BigDecimal> perSite = values.get(memberId);
            return perSite == null ? null : perSite.get(worksiteId);
        }
    }

    /**
     * Everything the planner needs about the submitted day and the stored day.
     *
     * @param newSites          member → worksites in the submitted assignments
     * @param newOvertime       member → submitted overtime total (exact, negatives already clamped); absent means 0
     * @param existingRows      assignment rows currently stored for the date
     * @param requests          submitted allocation intents (may be null)
     * @param memberNames       for error messages
     * @param worksiteNames     for error messages
     */
    public record Input(Map<Long, Set<Long>> newSites,
                        Map<Long, BigDecimal> newOvertime,
                        List<WorkAssignment> existingRows,
                        List<MemberAllocationDTO> requests,
                        Map<Long, String> memberNames,
                        Map<Long, String> worksiteNames) {
    }

    private record Stored(Map<Long, BigDecimal> values, boolean conflicting) {
    }

    private final Input in;
    private final List<AllocationErrorDTO> errors = new ArrayList<>();
    /** Members whose allocation entry itself is invalid; nothing else is checked for them. */
    private final Set<Long> rejectedEntries = new HashSet<>();

    private AllocationPlanner(Input in) {
        this.in = in;
    }

    public static Plan plan(Input input) {
        return new AllocationPlanner(input).run();
    }

    private Plan run() {
        Map<Long, MemberAllocationDTO> requested = indexRequests();
        Map<Long, Stored> storedRegular = storedValues(WorkAssignment::getRegularHoursOverride);
        Map<Long, Stored> storedOvertime = storedValues(WorkAssignment::getOvertimeHoursOverride);

        SortedSet<Long> members = new TreeSet<>();
        members.addAll(in.newSites().keySet());
        members.addAll(storedRegular.keySet());
        members.addAll(storedOvertime.keySet());
        members.addAll(requested.keySet());

        Map<Long, Map<Long, BigDecimal>> regular = new HashMap<>();
        Map<Long, Map<Long, BigDecimal>> overtime = new HashMap<>();
        for (Long memberId : members) {
            if (rejectedEntries.contains(memberId)) {
                continue;
            }
            MemberAllocationDTO request = requested.get(memberId);
            resolve(memberId, Category.REGULAR, request == null ? null : request.getRegular(),
                    storedRegular.get(memberId), regular);
            resolve(memberId, Category.OVERTIME, request == null ? null : request.getOvertime(),
                    storedOvertime.get(memberId), overtime);
        }

        if (!errors.isEmpty()) {
            throw new AllocationValidationException(errors);
        }
        return new Plan(regular, overtime);
    }

    private Map<Long, MemberAllocationDTO> indexRequests() {
        Map<Long, MemberAllocationDTO> byMember = new HashMap<>();
        if (in.requests() == null) {
            return byMember;
        }
        Set<Long> duplicates = new TreeSet<>();
        for (MemberAllocationDTO request : in.requests()) {
            if (request == null || request.getTeamMemberId() == null) {
                reject(null, null, "INVALID_ALLOCATION",
                        "Every allocation entry needs a teamMemberId.");
                continue;
            }
            Long memberId = request.getTeamMemberId();
            if (byMember.putIfAbsent(memberId, request) != null) {
                duplicates.add(memberId);
            }
            List<String> repeated = request.getDuplicateFields();
            if (repeated != null) {
                for (String field : repeated) {
                    rejectedEntries.add(memberId);
                    reject(memberId, categoryOf(field), "DUPLICATE_FIELD",
                            name(memberId) + ": \"" + field
                                    + "\" appears more than once in the allocation entry; send it once.");
                }
            }
        }
        for (Long memberId : duplicates) {
            rejectedEntries.add(memberId);
            reject(memberId, null, "DUPLICATE_MEMBER",
                    name(memberId) + " appears more than once in allocations; send one entry per worker.");
        }
        return byMember;
    }

    private static Category categoryOf(String fieldPath) {
        for (Category category : Category.values()) {
            String key = category.name().toLowerCase(Locale.ROOT);
            if (fieldPath.equals(key) || fieldPath.startsWith(key + ".")) {
                return category;
            }
        }
        return null;
    }

    private Map<Long, Stored> storedValues(Function<WorkAssignment, BigDecimal> getter) {
        Map<Long, Map<Long, BigDecimal>> values = new HashMap<>();
        Set<Long> conflicting = new HashSet<>();
        for (WorkAssignment row : in.existingRows()) {
            BigDecimal value = getter.apply(row);
            if (value == null) {
                continue;
            }
            Long memberId = row.getTeamMember().getId();
            Long worksiteId = row.getMasterWorksite().getId();
            BigDecimal previous = values.computeIfAbsent(memberId, k -> new HashMap<>())
                    .putIfAbsent(worksiteId, DecimalHours.normalize(value));
            if (previous != null && previous.compareTo(value) != 0) {
                conflicting.add(memberId);
            }
        }
        Map<Long, Stored> result = new HashMap<>();
        values.forEach((memberId, perSite) ->
                result.put(memberId, new Stored(perSite, conflicting.contains(memberId))));
        return result;
    }

    private void resolve(Long memberId, Category category, CategoryAllocationDTO request,
                         Stored stored, Map<Long, Map<Long, BigDecimal>> out) {
        boolean preserved = request == null;
        if (preserved && stored == null) {
            return;
        }
        boolean custom = preserved || AllocationMode.CUSTOM.name().equals(request.getMode());

        // A custom overtime split is only possible when the total itself is supported.
        BigDecimal target = DecimalHours.REGULAR_DAY_HOURS;
        if (category == Category.OVERTIME) {
            target = in.newOvertime().getOrDefault(memberId, BigDecimal.ZERO);
            if (custom && !DecimalHours.isExactDouble(target)) {
                reject(memberId, category, "OVERTIME_TOTAL_NOT_REPRESENTABLE",
                        name(memberId) + "'s overtime total " + DecimalHours.display(target)
                                + " cannot be stored exactly, so it cannot have a custom split. Use "
                                + storedFormOf(target) + " instead.");
                return;
            }
            if (custom && !DecimalHours.fitsStorage(target)) {
                reject(memberId, category, "OVERTIME_TOTAL_UNSUPPORTED",
                        name(memberId) + "'s overtime total " + DecimalHours.display(target)
                                + " has more than " + DecimalHours.STORAGE_SCALE
                                + " decimal places, so it cannot have a custom split. Keep overtime automatic.");
                return;
            }
        }

        Map<Long, BigDecimal> values;
        if (preserved) {
            if (stored.conflicting()) {
                reject(memberId, category, "STORED_ALLOCATION_CONFLICT",
                        name(memberId) + " has conflicting saved " + category.label
                                + " values for the same worksite. Reset the " + category.label
                                + " split in the planner.");
                return;
            }
            values = stored.values();
        } else {
            values = parseRequest(memberId, category, request);
            if (values == null) {
                return;
            }
        }

        Set<Long> sites = in.newSites().getOrDefault(memberId, Set.of());
        if (sites.isEmpty()) {
            reject(memberId, category, "NO_ASSIGNMENTS", preserved
                    ? name(memberId) + " has a saved custom " + category.label
                    + " split but no worksites on this day. Reset it to automatic (mode AUTO) to remove the worker."
                    : name(memberId) + " has no worksites on this day, so a custom " + category.label
                    + " split cannot be saved.");
            return;
        }
        if (!values.keySet().equals(sites)) {
            rejectCoverage(memberId, category, preserved, values.keySet(), sites);
            return;
        }

        BigDecimal sum = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(target) != 0) {
            reject(memberId, category, preserved ? "TOTAL_CHANGED" : "SUM_MISMATCH", preserved
                    ? name(memberId) + "'s saved custom " + category.label + " split adds up to "
                    + DecimalHours.toPlainString(sum) + "h, but the day's " + category.label + " total is now "
                    + DecimalHours.toPlainString(target) + "h. Update or reset the " + category.label
                    + " split in the planner."
                    : name(memberId) + "'s custom " + category.label + " split adds up to "
                    + DecimalHours.toPlainString(sum) + "h but must equal "
                    + DecimalHours.toPlainString(target) + "h.");
            return;
        }

        out.put(memberId, values);
    }

    /** Returns the submitted CUSTOM values, or null for AUTO or when the request is invalid. */
    private Map<Long, BigDecimal> parseRequest(Long memberId, Category category, CategoryAllocationDTO request) {
        String mode = request.getMode();
        List<SiteHoursDTO> hours = request.getHours();
        if (mode == null) {
            reject(memberId, category, "MISSING_MODE",
                    name(memberId) + ": " + category.label + " allocation needs mode AUTO or CUSTOM.");
            return null;
        }
        if (AllocationMode.AUTO.name().equals(mode)) {
            if (hours != null && !hours.isEmpty()) {
                reject(memberId, category, "AUTO_WITH_HOURS",
                        name(memberId) + ": an AUTO " + category.label + " allocation must not include hours.");
            }
            return null;
        }
        if (!AllocationMode.CUSTOM.name().equals(mode)) {
            reject(memberId, category, "INVALID_MODE",
                    name(memberId) + ": unknown " + category.label + " allocation mode '" + mode
                            + "'; use AUTO or CUSTOM.");
            return null;
        }
        if (hours == null || hours.isEmpty()) {
            reject(memberId, category, "EMPTY_ALLOCATION",
                    name(memberId) + ": a CUSTOM " + category.label + " allocation needs hours for every worksite.");
            return null;
        }

        Map<Long, BigDecimal> values = new LinkedHashMap<>();
        Set<Long> duplicates = new TreeSet<>();
        boolean valid = true;
        for (SiteHoursDTO entry : hours) {
            if (entry == null || entry.getMasterWorksiteId() == null) {
                reject(memberId, category, "INVALID_ENTRY",
                        name(memberId) + ": every " + category.label + " entry needs a masterWorksiteId.");
                valid = false;
                continue;
            }
            Long worksiteId = entry.getMasterWorksiteId();
            BigDecimal value = entry.getHours();
            if (value == null) {
                reject(memberId, category, "MISSING_HOURS",
                        name(memberId) + ": " + category.label + " for " + site(worksiteId) + " is missing.");
                valid = false;
            } else if (value.signum() < 0) {
                reject(memberId, category, "NEGATIVE_HOURS",
                        name(memberId) + ": " + category.label + " for " + site(worksiteId)
                                + " cannot be negative.");
                valid = false;
            } else if (!DecimalHours.fitsStorage(value)) {
                reject(memberId, category, "UNSUPPORTED_PRECISION",
                        name(memberId) + ": " + category.label + " for " + site(worksiteId) + " ("
                                + DecimalHours.display(value) + ") has more than " + DecimalHours.STORAGE_SCALE
                                + " decimal places or more than 35 digits before the decimal point.");
                valid = false;
            }
            if (values.containsKey(worksiteId)) {
                duplicates.add(worksiteId);
            } else {
                values.put(worksiteId, value);
            }
        }
        for (Long worksiteId : duplicates) {
            reject(memberId, category, "DUPLICATE_WORKSITE",
                    name(memberId) + ": " + site(worksiteId) + " is listed more than once in the "
                            + category.label + " allocation.");
            valid = false;
        }
        if (!valid) {
            return null;
        }
        values.replaceAll((worksiteId, value) -> DecimalHours.normalize(value));
        return values;
    }

    private void rejectCoverage(Long memberId, Category category, boolean preserved,
                                Set<Long> allocated, Set<Long> assigned) {
        Set<Long> missing = new TreeSet<>(assigned);
        missing.removeAll(allocated);
        Set<Long> extra = new TreeSet<>(allocated);
        extra.removeAll(assigned);

        StringBuilder detail = new StringBuilder();
        if (!missing.isEmpty()) {
            detail.append(" Missing: ").append(sites(missing)).append('.');
        }
        if (!extra.isEmpty()) {
            detail.append(" Not assigned: ").append(sites(extra)).append('.');
        }
        if (preserved) {
            reject(memberId, category, "WORKSITES_CHANGED",
                    name(memberId) + "'s worksites changed, so the saved custom " + category.label
                            + " split no longer matches." + detail
                            + " Update or reset the " + category.label + " split in the planner.");
        } else {
            reject(memberId, category, "COVERAGE_MISMATCH",
                    name(memberId) + ": the custom " + category.label
                            + " split must list exactly the worker's worksites for this day." + detail);
        }
    }

    private static String storedFormOf(BigDecimal value) {
        double d = DecimalHours.toDouble(value);
        return Double.isFinite(d) ? DecimalHours.display(DecimalHours.canonical(d)) : "a smaller value";
    }

    private void reject(Long memberId, Category category, String code, String message) {
        errors.add(new AllocationErrorDTO(
                memberId,
                memberId == null ? null : in.memberNames().get(memberId),
                category == null ? null : category.name(),
                code,
                message));
    }

    private String name(Long memberId) {
        String name = in.memberNames().get(memberId);
        return name != null ? name : "Worker #" + memberId;
    }

    private String site(Long worksiteId) {
        String name = in.worksiteNames().get(worksiteId);
        return name != null ? name + " (#" + worksiteId + ")" : "worksite #" + worksiteId;
    }

    private String sites(Set<Long> worksiteIds) {
        return worksiteIds.stream().map(this::site).collect(Collectors.joining(", "));
    }
}
