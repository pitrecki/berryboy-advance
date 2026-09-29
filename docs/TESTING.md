# Testy JUnit 5

Wymagane: Linux, JDK 21 i Maven 3.9.x. Testy nie wymagają własnego BIOS-u ani ROM-u.
Używają krótkich programów ARM/Thumb i oryginalnego `DemoRom` z projektu.

## Standardowy przebieg

```bash
mvn test
mvn verify
```

`mvn test` kompiluje kod i uruchamia Jupiter przez Surefire. `mvn verify` dodatkowo
buduje JAR aplikacji. Nie ma niestandardowego runnera ani wymuszonego pomijania
testów. Raporty tekstowe i XML powstają w `target/surefire-reports`.

```bash
mvn -Dtest=ArmShiftTest test
mvn -Dtest=CpuStatusTest#preservesSystemAndFiqRegisterBanks test
mvn -Dgroups=core test
mvn -Dgroups=build test
mvn -Dgroups=desktop test
```

`./test.sh` przekazuje opcje do `mvn verify`, np. `./test.sh -Dgroups=core`.

## Podział scenariuszy

| Klasa | Zakres |
|---|---|
| `ArmArithmeticTest` | 512 zestawów operandów, wynik i NZCV; dekodowanie immediate; logika |
| `ArmConditionTest` | 16 warunków × 16 kombinacji NZCV |
| `ArmShiftTest` | 17 brzegowych przesunięć, RRX i odczyty PC |
| `ArmTransferTest` | Niewyrównane odczyty, writeback, stos, SWP i rejestr bazowy na liście |
| `ArmMultiplyTest` | MUL/MLA i mnożenie signed/unsigned long |
| `CpuStatusTest` | CPSR, banki FIQ/System, SWI i powrót do Thumb |
| `CpuFaultTest` | Brak BIOS-u/SPSR i nieobsługiwane BLX; diagnostyka PC/opkodu |
| `CpuExecutionTest` | Program mieszający ARM i Thumb; skok po underflow |
| `ThumbInstructionTest` | Arytmetyka, stos, BL, wysokie rejestry i transfery |
| `MemoryTest` | Endianowość, mirroring, byte stores i KEYINPUT |
| `CartridgeTest` | Nagłówek i suma kontrolna |
| `BitmapRendererTest` | Strony bitmapy, paleta, granice trybu 5 i forced blank |
| `DemoRomTest` | Pełny program demo i przenośna diagnostyka |
| `LegacyExportTest` | `var`, returned switch oraz odrzucanie nieobsługiwanej składni/API |
| `LegacyCoreTest` | Eksport i kompilacja całego rdzenia, uruchomienie w izolacji |
| `desktop/LaunchCommandTest` | Typowane komendy CLI i odrzucanie błędnych argumentów |

`EmulatorFixtures` przechowuje wyłącznie przygotowanie obrazu ROM i sprzętu.
`CompilerFixture` uruchamia rzeczywisty javac dla wygenerowanych źródeł. Asercje
korzystają z JUnit: `assertEquals`, `assertAll`, `assertThrows` i pozostałych standardowych metod.
Każdy przypadek dostaje świeży stan maszyny. Eksporter używa `@TempDir`.

Przypadki losowe arytmetyki mają stały seed `0xbba11`, więc są powtarzalne.
Dotychczasowe scenariusze zostały zachowane; porównania CMP/CMN dodatkowo sprawdzają,
czy rejestr docelowy pozostał niezmieniony. Liczba testów oznacza teraz wykonania
JUnit, a nie ręcznie naliczane asercje z wcześniejszych wydań.

## Pełne powtórzenie na wyeksportowanym rdzeniu

Do tej ścieżki potrzebny jest oficjalny
`junit-platform-console-standalone-1.13.4.jar`. Można pobrać go przez Maven:

```bash
mvn org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy \
  -Dartifact=org.junit.platform:junit-platform-console-standalone:1.13.4 \
  -DoutputDirectory=target/test-tools

JUNIT_CONSOLE_JAR="$PWD/target/test-tools/junit-platform-console-standalone-1.13.4.jar" \
  tools/test-with-console.sh
```

Skrypt kompiluje źródła na Java 21, uruchamia Jupiter, eksportuje rdzeń, kompiluje
wygenerowane źródła z `--release 8`, a następnie uruchamia testy z tagiem `core`
z wygenerowanymi klasami na początku classpath. Raporty są w
`target/junit-console/reports/host` oraz `target/junit-console/reports/legacy`.

To kontrola semantyki na JVM hosta. Nie zastępuje kompilacji z CLDC SDK, RAPC ani
uruchomienia MIDleta w rzeczywistym runtime BB.

## Symulator BlackBerry

```bash
mvn -Pbb-sim verify
```

Profil najpierw uruchamia testy JUnit na hoście, następnie eksportuje rdzeń i używa
lokalnego SDK przez Wine. `RuntimeChecks` w MIDlecie jest osobną, przenośną
diagnostyką urządzenia. JUnit nie jest pakowany do aplikacji na BlackBerry.
Konfiguracja: [BLACKBERRY.md](BLACKBERRY.md).

## Dokumentacja narzędzi

- [JUnit 5.13.4 User Guide](https://docs.junit.org/5.13.4/user-guide/index.html)
- [Maven Surefire: JUnit Platform](https://maven.apache.org/surefire/maven-surefire-plugin/examples/junit-platform.html)
