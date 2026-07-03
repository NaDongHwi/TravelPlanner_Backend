package com.travel.planner.entity;

public enum Region {
    HOKKAIDO("홋카이도 지방"),
    TOHOKU("도호쿠 지방"),
    KANTO("간토 지방"),
    CHUBU("주부 지방"),
    KANSAI("간사이 지방"),
    CHUGOKU("주고쿠 지방"),
    SHIKOKU("시코쿠 지방"),
    KYUSHU("규슈 지방"),
    OKINAWA("오키나와 지방");

    private final String description;

    Region(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}