package com.gathin.tvhome;

/** Тесты правил пульта. Запуск: starwind/test/run.sh */
public final class NavTest {
    private static int failed;

    private static IosNav.Screen screen(int zone, int index) {
        IosNav.Screen s = new IosNav.Screen();
        s.zone = zone; s.index = index; s.selected = 1; s.services = 7; s.cards = 10; s.remembered = 4;
        s.apps = 19; s.perRow = 8; s.top = 3;
        return s;
    }

    private static void check(String what, IosNav.Screen s, int key, String want) {
        String got = IosNav.next(s, key).toString();
        if (!got.equals(want)) { failed++; System.out.println("ПРОВАЛ " + what + ": ждали " + want + ", получили " + got); }
        else System.out.println("ок     " + what);
    }

    public static void main(String[] a) {
        int D = IosNav.DOCK, C = IosNav.CARDS, A = IosNav.APPS, T = IosNav.TOP;
        int L = IosNav.LEFT, R = IosNav.RIGHT, U = IosNav.UP, Dn = IosNav.DOWN;
        check("док: вправо в ленту, на запомненную карточку", screen(D, 1), R, "go 1:4");
        check("док: вниз на следующий сервис", screen(D, 1), Dn, "go 0:2");
        check("док: с последнего сервиса вниз на кнопку приложений", screen(D, 6), Dn, "go 0:7");
        check("док: с кнопки приложений вниз некуда", screen(D, 7), Dn, "stay");
        check("док: с кнопки приложений вправо в список", screen(D, 7), R, "go 2:0");
        check("док: с первого вверх к значкам", screen(D, 0), U, "go 3:0");
        check("лента: вниз в ленту следующего сервиса, не в док", screen(C, 6), Dn, "feed 2");
        check("лента: вверх в ленту предыдущего сервиса", screen(C, 6), U, "feed 0");
        IosNav.Screen first = screen(C, 2); first.selected = 0;
        check("лента первого сервиса: вверх к значкам", first, U, "go 3:0");
        IosNav.Screen last = screen(C, 2); last.selected = 6;
        check("лента последнего сервиса: вниз на кнопку приложений", last, Dn, "go 0:7");
        check("лента: влево с первой карточки в док", screen(C, 0), L, "go 0:1");
        check("лента: вправо на последней некуда", screen(C, 9), R, "stay");
        check("приложения: влево из первого столбца всегда в док", screen(A, 8), L, "go 0:7");
        check("приложения: влево из первого столбца первого ряда в док", screen(A, 0), L, "go 0:7");
        check("приложения: вправо с конца ряда на следующий ряд", screen(A, 7), R, "go 2:8");
        check("приложения: вправо с последней плитки некуда", screen(A, 18), R, "stay");
        check("приложения: вверх из первого ряда к последнему сервису", screen(A, 3), U, "go 0:6");
        check("приложения: вниз, если под иконкой пусто, на последнюю", screen(A, 13), Dn, "go 2:18");
        check("приложения: вниз из последнего ряда некуда", screen(A, 17), Dn, "stay");
        check("значки: влево с первого в док", screen(T, 0), L, "go 0:1");
        check("значки: вниз в ленту", screen(T, 1), Dn, "go 1:4");
        System.out.println(failed == 0 ? "ВСЕ ТЕСТЫ ПРОШЛИ" : "ПРОВАЛЕНО: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
