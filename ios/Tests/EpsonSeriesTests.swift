import XCTest
@testable import DelicityCapacitorThermalPrinter

/// Déduction de la série ePOS2 depuis un nom de modèle (miroir de EpsonAdapterTest Android).
final class EpsonSeriesTests: XCTestCase {

    func testRevisionsAreStrippedDownToKnownSeries() {
        XCTAssertEqual(epsonSeriesCandidates("TM-T20II"), ["TM_T20II", "TM_T20I", "TM_T20"])
        XCTAssertEqual(epsonSeriesCandidates("TM-m30III"), ["TM_M30III", "TM_M30II", "TM_M30I", "TM_M30"])
        XCTAssertEqual(epsonSeriesCandidates("Epson TM-m30"), ["TM_M30"])
    }

    func testGenericNamesGiveNoCandidate() {
        XCTAssertEqual(epsonSeriesCandidates("TM Printer"), [])
        XCTAssertEqual(epsonSeriesCandidates(""), [])
    }
}
