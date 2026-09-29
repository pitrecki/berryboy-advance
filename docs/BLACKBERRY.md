# Linux / Java 21 / BlackBerry OS 7.1

## Trzy różne runtime'y

- JVM 21 na Linuksie: Maven, testy, podgląd i eksporter.
- Narzędzia kompilacyjne JDK 6 + BlackBerry JDE 7.1, uruchomione w Wine:
  generują kod dla starego runtime. Nie muszą być domyślną Javą systemu.
- Runtime symulatora / telefonu: wykonuje `.cod`, a nie klasy Java 21.

RIM udostępniał windowsowy symulator klasycznego BBOS. Profil `bb-sim` jest
integracją tych narzędzi przez Wine. Nie jest nowym natywnym portem symulatora
na Linuksa; zgodność konkretnego wydania symulatora z Wine wymaga sprawdzenia.
Symulator BB10 nie zastępuje symulatora BBOS 7.1 dla Bolda 9900.

## Lokalne wymagania

1. Maven 3.9.x uruchomiony na JDK 21; sprawdź `mvn -v`.
2. Wine z obsługą 32-bitowych programów i skonfigurowanym środowiskiem graficznym.
3. Legalnie uzyskane BlackBerry JDE 7.1 (`bin/rapc.exe`, `lib/net_rim_api.jar`).
4. Windowsowy JDK 6 w tym samym środowisku Wine (`bin/javac.exe`, `bin/jar.exe`).
5. Symulator Bold 9900 z `9900.bat` i `fledgecontroller.exe`.

Wszystkie zmienne `BB_*` są **linuksowymi ścieżkami** do katalogów tych instalacji.
Opcjonalny `WINEPREFIX` musi wskazywać ten sam prefix przy instalacji i buildzie.
Skrypt używa `winepath` do konwersji ścieżek i nie modyfikuje globalnego `JAVA_HOME`.

```bash
export BB_JDE_HOME="$HOME/.wine-bb/drive_c/BlackBerry JDE 7.1.0"
export BB_JDK6_HOME="$HOME/.wine-bb/drive_c/Java/jdk1.6.0_45"
export BB_SIMULATOR_HOME="$HOME/.wine-bb/drive_c/BlackBerry Simulators/9900"
export WINEPREFIX="$HOME/.wine-bb"
mvn -Pbb-sim verify
```

Można też przekazać ścieżki przez `-Dbb.sdk=...`, `-Dbb.jdk6=...`,
`-Dbb.simulator=...`. `-Dbb.model=9900` ustala nazwę launchera i sesji.
Jeśli launcher ma inną nazwę sesji, dostosuj skrypt lub launcher lokalnie.

Samo zbudowanie bez uruchamiania symulatora:

```bash
mvn -Pbb-sim verify -Dbb.launch=false
```

## Co robi profil

1. Kompiluje i testuje współczesny projekt na JDK 21.
2. Eksportuje wspólny rdzeń do `target/blackberry/generated/berryboy`.
3. Uruchamia w Wine `javac -source 1.4 -target 1.4` z API BlackBerry jako bootclasspath.
4. Dołącza mały adapter `BlackBerryMidlet`, pakuje JAR i konwertuje go przez RAPC.
5. Uruchamia oryginalny `9900.bat` i wysyła `LoadCod(...)` do kontrolera symulatora.
6. Użytkownik otwiera ikonę BerryBoy Advance. MIDlet uruchamia lokalnie osiem
   kontroli i program demo. Można ponowić je poleceniem `Run checks`.

Rezultaty: `target/blackberry/output/`, `simulator.log`, `controller.log`.
Skrypt usuwa wyłącznie własne wygenerowane klasy i własny katalog output.
Nie zamyka istniejącej sesji, nie resetuje flasha symulatora i nie instaluje nic
na fizycznym telefonie. Najprościej uruchomić go po zamknięciu starej sesji 9900.

**Stan: skrypt sprawdzony składniowo, ale nie uruchomiony z Wine/JDE/symulatorem.**
RAPC może wymagać dopasowania ścieżek, preweryfikacji lub sposobu ładowania
podmodułów COD w konkretnym SDK. Nie oznaczamy testu BB jako zaliczonego na
podstawie samego wyjścia kontrolera; wynik musi pokazać aplikacja.

## Zakres eksportu źródeł

| Konstrukcja | Współdzielony rdzeń |
|---|---|
| Typy prymitywne, tablice, klasy, metody, zwykłe pętle | Tak |
| Lokalne `var` | Tak: typ rozwiązuje javac |
| `return switch (int) { case ... -> wyrażenie; }` | Tak: eksport na klasyczny switch |
| Kilka stałych w jednej etykiecie `case` | Tak: kilka etykiet starego switcha |
| Lambdy / method references | Odrzucane |
| Rekordy, generyki, pattern matching | Odrzucane |
| Try-with-resources / enhanced for | Odrzucane |
| Nowe API (`String.repeat`, kolekcje, streams, NIO) | Nieprzenośne; gate API / kompilator SDK |
| Dowolny kod Java 21 | Nie; to nie implementacja JVM 21 |

Frontend i build tools nie są eksportowane: mogą używać nowoczesnej Javy bez
ograniczeń wymuszonych przez telefon. Wydajność rzeczywistego Bolda nie była mierzona.

## Dokumentacja źródłowa

- [BlackBerry JDE — oficjalne przykłady](https://github.com/blackberry/JDE-Samples)
- [RIM Simulator User Guide, zachowana kopia](https://device.report/m/43910e81f5369f8768176fc5b78a752fef304c90d0e777e0150ce082949a7c82)
- [RIM MIDlet Developer Guide, zachowana kopia](https://www.scribd.com/doc/60656300/Blackberry-MIDlet-Developer-Guide)
- [CLDC 1.1 java.lang](https://docs.oracle.com/javame/config/cldc/ref-impl/cldc1.1/jsr139/java/lang/package-summary.html)
- [JavacTask API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.compiler/com/sun/source/util/JavacTask.html)
- [Wine](https://www.winehq.org/about)
