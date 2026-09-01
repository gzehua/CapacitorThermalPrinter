import Foundation
import Network

/// Découverte réseau iOS.
///
/// Recommandé : Bonjour/mDNS via NWBrowser pour `_pdl-datastream._tcp` (port 9100)
/// et `_printer._tcp` / `_ipp._tcp`. C'est la méthode propre sur iOS (pas de scan
/// d'IP brute, qui est mal vu et lent). Les imprimantes réseau modernes publient
/// ces services.
///
/// ⚠️ Info.plist : nécessite NSLocalNetworkUsageDescription + NSBonjourServices
/// listant les services recherchés.
final class BonjourScanner {

    private var browsers: [NWBrowser] = []
    // Meilleur rang de service déjà émis par nom d'imprimante (muté sur `queue` uniquement).
    private var bestRank: [String: Int] = [:]

    /// Rang de préférence d'un service Bonjour pour de l'impression RAW ESC/POS.
    /// `_pdl-datastream._tcp` = port 9100 RAW : le seul qui accepte un flux ESC/POS brut.
    /// `_printer._tcp` (LPD 515) et `_ipp._tcp` (IPP 631) attendent leur protocole : y
    /// envoyer de l'ESC/POS brut fait imprimer le payload raster EN TEXTE (charabia).
    /// Une même imprimante publie souvent les trois sous le même nom → même id → sans
    /// classement, l'entrée gardée dépendait de l'ordre d'arrivée mDNS (non déterministe).
    static func serviceRank(_ type: String) -> Int {
        if type.hasPrefix("_pdl-datastream.") { return 0 }
        if type.hasPrefix("_printer.") { return 1 }
        return 2 // _ipp et le reste
    }

    /// Rang d'une adresse `bonjour:name\u{1}type\u{1}domain` (0 = meilleur ; non-Bonjour = 0).
    static func addressRank(_ address: String) -> Int {
        guard address.hasPrefix("bonjour:") else { return 0 }
        let parts = address.dropFirst("bonjour:".count).components(separatedBy: "\u{1}")
        return parts.count == 3 ? serviceRank(parts[1]) : 0
    }

    func scan(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        let services = ["_pdl-datastream._tcp", "_printer._tcp", "_ipp._tcp"]
        let queue = DispatchQueue(label: "thermalprinter.bonjour")
        bestRank = [:]

        for service in services {
            let params = NWParameters()
            params.includePeerToPeer = false
            let browser = NWBrowser(for: .bonjour(type: service, domain: nil), using: params)
            browser.browseResultsChangedHandler = { [weak self] results, _ in
                for result in results {
                    if case let .service(name, type, domain, _) = result.endpoint {
                        // N'émettre que si ce service est meilleur que ce qu'on a déjà vu
                        // pour ce nom (tous les handlers tournent sur `queue`, accès sûr).
                        let rank = Self.serviceRank(type)
                        if let known = self?.bestRank[name], known <= rank { continue }
                        self?.bestRank[name] = rank
                        // On encode name/type/domain : `TcpTransport.make` reconstruit un
                        // `NWEndpoint.service` et résout l'IP à la connexion. (Encoder juste
                        // "name._type" donnait une fausse adresse hôte -> connexion timeout.)
                        let address = "bonjour:" + [name, type, domain].joined(separator: "\u{1}")
                        let printer = DiscoveredPrinter(
                            id: "wifi:\(name)",
                            name: name,
                            brand: nil, model: nil,
                            transport: .wifi,
                            adapter: .escpos,           // arbitré ensuite par la priorité
                            address: address,
                            capabilities: Capabilities(),
                            discoveredBy: ["escpos", "rawTcp"]
                        )
                        onFound(printer)
                    }
                }
            }
            browser.start(queue: queue)
            browsers.append(browser)
        }

        try? await Task.sleep(nanoseconds: UInt64(timeoutMs) * 1_000_000)
        browsers.forEach { $0.cancel() }
        browsers.removeAll()
    }
}
