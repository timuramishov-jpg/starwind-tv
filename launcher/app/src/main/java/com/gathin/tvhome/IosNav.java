package com.gathin.tvhome;

/**
 * Все правила пульта в одном месте. Чистая логика без Android: её проверяют тесты в starwind/test.
 * Зоны: док (последний пункт: кнопка всех приложений), лента, значки сверху, список приложений.
 */
final class IosNav {
    static final int DOCK = 0, CARDS = 1, APPS = 2, TOP = 3;
    static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;

    /** Куда идти: в зону и позицию, в ленту соседнего сервиса или никуда. */
    static final class Move {
        static final int STAY = 0, GO = 1, FEED = 2;
        final int kind, zone, index;
        Move(int kind, int zone, int index) { this.kind = kind; this.zone = zone; this.index = index; }
        static Move go(int zone, int index) { return new Move(GO, zone, index); }
        static Move feed(int service) { return new Move(FEED, CARDS, service); }
        static final Move STAY_PUT = new Move(STAY, -1, -1);
        @Override public String toString() { return kind == STAY ? "stay" : kind == FEED ? "feed " + index : "go " + zone + ":" + index; }
    }

    /** Что видно на экране сейчас. */
    static final class Screen {
        int zone, index;
        int selected;      // выбранный сервис в доке
        int services;      // сервисов в доке, без кнопки приложений
        int cards;         // карточек в ленте выбранного сервиса
        int remembered;    // карточка, на которой остановились в этой ленте
        int apps;          // плиток в списке приложений
        int perRow;
        int top;           // значков сверху
    }

    static Move next(Screen s, int key) {
        switch (s.zone) {
            case DOCK: return dock(s, key);
            case CARDS: return cards(s, key);
            case TOP: return top(s, key);
            default: return apps(s, key);
        }
    }

    private static Move dock(Screen s, int key) {
        boolean grid = s.index == s.services;
        switch (key) {
            case UP: return s.index > 0 ? Move.go(DOCK, s.index - 1) : Move.go(TOP, 0);
            case DOWN: return s.index < s.services ? Move.go(DOCK, s.index + 1) : Move.STAY_PUT;
            case RIGHT:
                if (grid) return s.apps > 0 ? Move.go(APPS, 0) : Move.STAY_PUT;
                return s.cards > 0 ? Move.go(CARDS, clamp(s.remembered, s.cards)) : Move.STAY_PUT;
            default: return Move.STAY_PUT;
        }
    }

    private static Move cards(Screen s, int key) {
        switch (key) {
            case LEFT: return s.index > 0 ? Move.go(CARDS, s.index - 1) : Move.go(DOCK, s.selected);
            case RIGHT: return s.index < s.cards - 1 ? Move.go(CARDS, s.index + 1) : Move.STAY_PUT;
            case UP: return s.selected > 0 ? Move.feed(s.selected - 1) : Move.go(TOP, 0);
            default: return s.selected + 1 < s.services ? Move.feed(s.selected + 1) : Move.go(DOCK, s.services);
        }
    }

    private static Move top(Screen s, int key) {
        switch (key) {
            case LEFT: return s.index > 0 ? Move.go(TOP, s.index - 1) : Move.go(DOCK, s.selected);
            case RIGHT: return s.index < s.top - 1 ? Move.go(TOP, s.index + 1) : Move.STAY_PUT;
            case DOWN: return s.cards > 0 ? Move.go(CARDS, clamp(s.remembered, s.cards)) : Move.go(DOCK, s.selected);
            default: return Move.STAY_PUT;
        }
    }

    private static Move apps(Screen s, int key) {
        int col = s.index % s.perRow, row = s.index / s.perRow, lastRow = (s.apps - 1) / s.perRow;
        switch (key) {
            case LEFT: return col > 0 ? Move.go(APPS, s.index - 1) : Move.go(DOCK, s.services);
            case RIGHT: return s.index + 1 < s.apps ? Move.go(APPS, s.index + 1) : Move.STAY_PUT;
            case UP: return row > 0 ? Move.go(APPS, s.index - s.perRow) : Move.go(DOCK, Math.max(0, s.services - 1));
            default:
                if (s.index + s.perRow < s.apps) return Move.go(APPS, s.index + s.perRow);
                return row < lastRow ? Move.go(APPS, s.apps - 1) : Move.STAY_PUT;
        }
    }

    private static int clamp(int i, int n) { return Math.max(0, Math.min(i, n - 1)); }
}
