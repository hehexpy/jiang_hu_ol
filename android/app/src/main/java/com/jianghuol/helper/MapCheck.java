package com.jianghuol.helper;

/** 地图必须同时有独有字形与独立场景正证据；任意冲突均UNKNOWN。 */
final class MapCheck {
    enum State { CITY, INN, UNKNOWN }
    static State classify(double citySuffix, double innSuffix, double cityScene, double innScene) {
        if (citySuffix >= .84 && cityScene >= .86 && innScene < .70) return State.CITY;
        if (innSuffix >= .84 && innScene >= .86 && cityScene < .70) return State.INN;
        return State.UNKNOWN;
    }
}
