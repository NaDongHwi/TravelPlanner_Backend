package com.travel.planner.transit;

import com.travel.planner.entity.TransitPattern;
import com.travel.planner.entity.TransitStop;

import java.util.Map;

/** GTFS 정리 결과(GtfsFeed) ↔ DB 행(transit_stop, transit_pattern) 변환. 배열은 쉼표로 이은 글자로 저장한다. */
public final class GtfsCodec {

    private GtfsCodec() {}

    public static TransitStop toRow(String feedKey, GtfsFeed.Stop stop) {
        TransitStop row = new TransitStop();
        row.setFeedKey(feedKey);
        row.setStopCode(cut(stop.id, 120));
        row.setName(cut(stop.name, 200));
        row.setNameKo(cut(stop.nameKo, 200));
        row.setLatitude(stop.latitude);
        row.setLongitude(stop.longitude);
        return row;
    }

    /** @param stopRowIds 피드 안의 정류장 번호(Stop.index) → 저장된 transit_stop.id */
    public static TransitPattern toRow(String feedKey, GtfsFeed.Pattern pattern, long[] stopRowIds) {
        TransitPattern row = new TransitPattern();
        row.setFeedKey(feedKey);
        row.setRouteCode(cut(pattern.routeId, 120));
        row.setRouteName(cut(pattern.routeName, 200));
        row.setRouteLongName(cut(pattern.routeLongName, 300));
        row.setAgency(cut(pattern.agency, 200));
        row.setCategory(pattern.category.name());
        row.setHeadsign(cut(pattern.headsign, 300));

        StringBuilder ids = new StringBuilder();
        StringBuilder minutes = new StringBuilder();
        StringBuilder flags = new StringBuilder();
        boolean anyFlag = false;
        for (int i = 0; i < pattern.stops.length; i++) {
            if (i > 0) {
                ids.append(',');
                minutes.append(',');
                flags.append(',');
            }
            ids.append(stopRowIds[pattern.stops[i]]);
            minutes.append(Math.round(pattern.cumMinutes[i] * 10));
            int flag = pattern.flags == null ? 0 : pattern.flags[i];
            flags.append(flag);
            anyFlag |= flag != 0;
        }
        row.setStopIds(ids.toString());
        row.setCumMinutes(minutes.toString());
        row.setStopFlags(anyFlag ? flags.toString() : null);

        row.setTripsWeekday(pattern.trips[0]);
        row.setTripsSaturday(pattern.trips[1]);
        row.setTripsHoliday(pattern.trips[2]);
        row.setFirstDeparture(pattern.firstDeparture);
        row.setLastDeparture(pattern.lastDeparture);
        row.setServiceFrom(pattern.serviceFrom);
        row.setServiceTo(pattern.serviceTo);
        row.setIrregular(pattern.irregular);
        row.setFlatFare(pattern.flatFare);
        row.setFareTable(pattern.fares == null ? null : join(pattern.fares));
        return row;
    }

    public static GtfsFeed.Stop toStop(TransitStop row, int index) {
        GtfsFeed.Stop stop = new GtfsFeed.Stop();
        stop.index = index;
        stop.id = row.getStopCode();
        stop.name = row.getName();
        stop.nameKo = row.getNameKo();
        stop.latitude = row.getLatitude();
        stop.longitude = row.getLongitude();
        return stop;
    }

    /**
     * @param indexByStopId transit_stop.id → 그래프의 정류장 번호
     * @return 정류장을 찾을 수 없거나 내용이 깨진 행이면 null
     */
    public static GtfsFeed.Pattern toPattern(TransitPattern row, Map<Long, Integer> indexByStopId, boolean feedExpired) {
        try {
            long[] ids = splitLongs(row.getStopIds());
            int[] minutes = splitInts(row.getCumMinutes());
            if (ids.length < 2 || minutes.length != ids.length) return null;
            GtfsFeed.Pattern pattern = new GtfsFeed.Pattern();
            pattern.stops = new int[ids.length];
            pattern.cumMinutes = new float[ids.length];
            for (int i = 0; i < ids.length; i++) {
                Integer index = indexByStopId.get(ids[i]);
                if (index == null) return null;
                pattern.stops[i] = index;
                pattern.cumMinutes[i] = minutes[i] / 10f;
            }
            if (row.getStopFlags() != null && !row.getStopFlags().isEmpty()) {
                int[] flags = splitInts(row.getStopFlags());
                if (flags.length != ids.length) return null;
                pattern.flags = new byte[flags.length];
                for (int i = 0; i < flags.length; i++) pattern.flags[i] = (byte) flags[i];
            }
            if (row.getFareTable() != null && !row.getFareTable().isEmpty()) {
                int[] fares = splitInts(row.getFareTable());
                if (fares.length != ids.length * (ids.length - 1) / 2) return null;
                pattern.fares = fares;
            }
            pattern.feedKey = row.getFeedKey();
            pattern.feedExpired = feedExpired;
            pattern.routeId = row.getRouteCode();
            pattern.routeName = row.getRouteName();
            pattern.routeLongName = row.getRouteLongName();
            pattern.agency = row.getAgency() == null ? "" : row.getAgency();
            pattern.category = category(row.getCategory());
            pattern.headsign = row.getHeadsign();
            pattern.trips[0] = orZero(row.getTripsWeekday());
            pattern.trips[1] = orZero(row.getTripsSaturday());
            pattern.trips[2] = orZero(row.getTripsHoliday());
            pattern.firstDeparture = orZero(row.getFirstDeparture());
            pattern.lastDeparture = orZero(row.getLastDeparture());
            pattern.serviceFrom = row.getServiceFrom();
            pattern.serviceTo = row.getServiceTo();
            pattern.irregular = Boolean.TRUE.equals(row.getIrregular());
            pattern.flatFare = row.getFlatFare();
            return pattern;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static RailCategory category(String name) {
        try {
            return RailCategory.valueOf(name);
        } catch (RuntimeException e) {
            return RailCategory.BUS;
        }
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String cut(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }

    static String join(int[] values) {
        StringBuilder sb = new StringBuilder(values.length * 4);
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.toString();
    }

    static int[] splitInts(String text) {
        if (text == null || text.isEmpty()) return new int[0];
        int count = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == ',') count++;
        int[] values = new int[count];
        int at = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == ',') {
                values[at++] = Integer.parseInt(text, start, i, 10);
                start = i + 1;
            }
        }
        return values;
    }

    static long[] splitLongs(String text) {
        if (text == null || text.isEmpty()) return new long[0];
        String[] parts = text.split(",");
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) values[i] = Long.parseLong(parts[i]);
        return values;
    }
}
