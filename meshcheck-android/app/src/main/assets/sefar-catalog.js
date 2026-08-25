(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.SefarCatalog = api;
})(typeof window !== "undefined" ? window : globalThis, function () {
  "use strict";

  // SOLE DATA SOURCE: user-provided "sefar milling-EN(3).pdf".
  // Product specifications below are transcribed only from pages 3-8.
  const SOURCE_DOCUMENT = "sefar milling-EN(3).pdf";

  const squareData = `
PA|PA-GG|3|PA-12GG-1800|1800|500|4.4|11.2|61
PA|PA-GG|3|PA-14GG-1600|1600|450|5|12.7|60
PA|PA-GG|3|PA-15GG-1400|1400|400|5.4|13.7|59
PA|PA-GG|3|PA-16GG-1320|1320|400|5.8|14.7|59
PA|PA-GG|3|PA-17GG-1250|1250|350|6.1|15.5|58
PA|PA-GG|3|PA-18GG-1180|1180|350|6.5|16.5|59
PA|PA-GG|3|PA-19GG-1120|1120|350|6.7|17|58
PA|PA-GG|3|PA-20GG-1000|1000|320|7.5|19.1|57
PA|PA-GG|3|PA-22GG-950|950|300|8|20.3|57
PA|PA-GG|3|PA-23GG-900|900|300|8.3|21.1|56
PA|PA-GG|3|PA-24GG-850|850|300|8.7|22.1|53
PA|PA-GG|3|PA-26GG-800|800|280|9.4|23.9|53
PA|PA-GG|3|PA-27GG-750|750|280|9.7|24.6|52
PA|PA-GG|3|PA-28GG-710|710|260|10.5|26.7|53
PA|PA-GG|3|PA-30GG-670|670|260|10.8|27.4|53
PA|PA-GG|3|PA-31GG-630|630|240|11.5|29.2|53
PA|PA-GG|3|PA-32GG-600|600|240|11.9|30.2|51
PA|PA-GG|3|PA-34GG-560|560|240|12.3|31.2|49
PA|PA-GG|3|PA-36GG-530|530|220|13.3|33.8|50
PA|PA-GG|3|PA-38GG-500|500|220|13.7|34.8|47
PA|PA-GG|3|PA-40GG-475|475|200|15|38.1|48
PA|PA-GG|3|PA-42GG-450|450|200|15.4|39.1|48
PA|PA-GG|3|PA-44GG-425|425|200|16|40.6|46
PA|PA-GG|3|PA-45GG-400|400|180|16.8|42.7|47
PA|PA-GG|3|PA-47GG-375|375|180|17.5|44.5|47
PA|PA-GG|3|PA-50GG-355|355|160|19.4|49.3|48
PA|PA-GG|3|PA-52GG-335|335|160|20.2|51.3|46
PA|PA-GG|3|PA-54GG-315|315|160|21|53.3|44
PA|PA-GG|3|PA-58GG-300|300|140|21.7|55.1|45
PA|PA-GG|3|PA-60GG-280|280|140|23.8|60.5|45
PA|PA-GG|3|PA-62GG-275|275|140|24.1|61.2|44
PA|PA-GG|3|PA-64GG-265|265|140|25|63.5|43
PA|PA-GG|3|PA-66GG-250|250|120|26.5|67.3|46
PA|PA-GG|3|PA-68GG-243|243|120|28|71.1|44
PA|PA-GG|3|PA-70GG-236|236|120|28|71.1|44
PA|PA-GG|3|PA-72GG-224|224|120|29|73.7|42
PA|PA-GG|3|PA-74GG-212|212|120|30|76.2|40
PA|PA-XXX|4|PA-3 XXX-300|300|140|21.7|55.1|45
PA|PA-XXX|4|PA-4 XXX-280|280|140|23.8|60.5|45
PA|PA-XXX|4|PA-5 XXX-250|250|120|26.5|67.3|46
PA|PA-XXX|4|PA-6 XXX-212|212|120|30|76.2|40
PA|PA-XXX|4|PA-7 XXX-200|200|120|30|76.2|39
PA|PA-XXX|4|PA-8 XXX-180|180|100|36|91.4|39
PA|PA-XXX|4|PA-8½ XXX-160|160|100|37.5|95.3|37
PA|PA-XXX|4|PA-9 XXX-150|150|90|41|104.1|38
PA|PA-XXX|4|PA-9½ XXX-140|140|90|44|111.8|37
PA|PA-XXX|4|PA-10 XXX-132|132|80|45|114.3|40
PA|PA-XXX|4|PA-10½XXX-125|125|80|48.8|124|37
PA|PA-XXX|4|PA-11 XXX-118|118|80|49|124.5|34
PA|PA-XXX|4|PA-12 XXX-112|112|70|55|140|38
PA|PA-XXX|4|PA-13 XXX-100|100|70|57|144.8|32
PA|PA-XXX|4|PA-14 XXX-95|95|70|60.6|153.9|33
PA|PA-XXX|4|PA-14½XXX-90|90|60|67|170.2|36
PA|PA-XXX|4|PA-17 XXX-85|85|60|68|172.7|35
PA|PA-MF|4|PA-3MF-300|300|120|23.5|59.5|51
PA|PA-MF|4|PA-4MF-280|280|120|25|63.5|51
PA|PA-MF|4|PA-5MF-250|250|100|28.5|72.5|51
PA|PA-MF|4|PA-6MF-212|212|100|31|78.7|44
PA|PA-MF|4|PA-7MF-200|200|100|32.5|82.5|44
PA|PA-MF|4|PA-8MF-180|180|90|36.5|92.5|43
PA|PA-MF|4|PA-8½MF-160|160|90|40|101.5|42
PA|PA-MF|4|PA-9MF-150|150|80|43|114|42
PA|PA-MF|4|PA-9½MF-140|140|80|45|114.5|41
PA|PA-MF|4|PA-10MF-132|132|70|49|124.5|43
PA|PA-MF|4|PA-10½MF-125|125|70|51|129.5|41
PA|PA-MF|4|PA-11MF-118|118|70|53|134.5|41
PA|PA-MF|4|PA-12MF-112|112|60|57.5|146|42
PA|PA-MF|4|PA-13MF-100|100|60|60|152.5|39
PA|PA-MF|4|PA-14MF-95|95|60|64|162.5|36
Metal|Metal Mesh|5|26-2000/74|2000|320|4.3|10.9|74.0
Metal|Metal Mesh|5|26-1800/72|1800|320|4.7|12.0|72.0
Metal|Metal Mesh|5|26-1600/77|1600|220|5.5|14.0|77.0
Metal|Metal Mesh|5|26-1400/74|1400|220|6.2|15.7|74.5
Metal|Metal Mesh|5|26-1250/72|1250|220|6.8|17.3|72.0
Metal|Metal Mesh|5|26-1180/71|1180|220|7.1|18.1|71.0
Metal|Metal Mesh|5|26-1120/69|1120|220|7.5|19.0|69.4
Metal|Metal Mesh|5|26-1060/68|1060|220|7.8|19.8|68.6
Metal|Metal Mesh|5|26-1000/67|1000|220|8.3|21.0|67.0
Metal|Metal Mesh|5|26-950/68|950|200|8.7|22.0|68.2
Metal|Metal Mesh|5|26-900/67|900|200|9.1|23.0|67.0
Metal|Metal Mesh|5|26-850/65|850|200|9.4|24.0|65.5
Metal|Metal Mesh|5|26-800/64|800|200|9.8|25.0|64.0
Metal|Metal Mesh|5|26-750/65|750|180|10.6|27.0|65.0
Metal|Metal Mesh|5|26-710/64|710|180|11.4|29.0|64.0
Metal|Metal Mesh|5|26-670/65|670|160|12.2|31.0|65.2
Metal|Metal Mesh|5|26-630/64|630|160|12.6|32.0|64.0
Metal|Metal Mesh|5|26-600/62|600|160|13.0|33.0|62.3
Metal|Metal Mesh|5|26-560/60|560|160|13.8|35.0|60.0
Metal|Metal Mesh|5|26-530/59|530|160|14.6|37.0|59.0
Metal|Metal Mesh|5|26-500/57|500|160|15.0|38.0|57.6
Metal|Metal Mesh|5|26-475/56|475|160|15.7|40.0|56.0
Metal|Metal Mesh|5|26-450/57|450|140|16.9|43.0|57.6
Metal|Metal Mesh|5|26-425/56|425|140|17.7|45.0|56.6
Metal|Metal Mesh|5|26-400/54|400|140|18.5|47.0|54.0
Metal|Metal Mesh|5|26-375/53|375|140|19.3|49.0|53.0
Metal|Metal Mesh|5|26-355/51|355|140|20.1|51.0|51.0
Metal|Metal Mesh|5|26-335/49|335|140|20.9|53.0|49.7
Metal|Metal Mesh|5|26-315/54|315|112|23.2|59.0|54.0
Metal|Metal Mesh|5|26-300/53|300|112|24.4|62.0|53.0
Metal|Metal Mesh|5|26-280/51|280|112|25.6|65.0|51.0
Metal|Metal Mesh|5|26-265/53|265|100|27.6|70.0|52.8
Metal|Metal Mesh|5|26-250/53|250|100|28.7|73.0|53.0
Metal|Metal Mesh|5|26-245/62|245|65|32.3|82.0|62.0
Metal|Metal Mesh|5|26-236/51|236|100|29.9|76.0|51.0
Metal|Metal Mesh|5|26-224/49|224|100|30.7|78.0|49.0
Metal|Metal Mesh|5|26-212/49|212|90|33.1|84.0|49.0
Metal|Metal Mesh|5|26-180/45|180|90|37.0|94.0|45.0
Metal|Metal Mesh|5|26-160/46|160|75|41.0|105.0|46.0
Metal|Metal Mesh|5|26-140/46|140|67|47.2|120.0|46.0
Metal|Metal Mesh|5|26-132/49|132|65|53.1|135.0|49.0
Metal|Metal Mesh|5|26-125/48|125|56|55.1|140.0|48.0
Metal|Metal Mesh|5|26-106/46|106|50|64.2|163.0|46.0
Metal|Metal Mesh Heavy|5|26-2000/64|2000|500|4.0|10.2|64
Metal|Metal Mesh Heavy|5|26-1800/58|1800|560|4.2|10.7|58
Metal|Metal Mesh Heavy|5|26-1600/58|1600|500|4.8|12.2|58
Metal|Metal Mesh Heavy|5|26-1400/57|1400|450|5.4|13.7|57
Metal|Metal Mesh Heavy|5|26-1250/57|1250|400|6.0|15.2|57
Metal|Metal Mesh Heavy|5|26-1180/56|1180|400|6.3|16.0|56
Metal|Metal Mesh Heavy|5|26-1120/61|1120|315|7.0|17.8|61
Metal|Metal Mesh Heavy|5|26-1000/58|1000|315|7.6|19.3|58
Metal|Metal Mesh Heavy|5|26-950/56|950|315|7.9|20.1|56
Metal|Metal Mesh Heavy|5|26-900/55|900|315|8.2|20.8|55
Metal|Metal Mesh Heavy|5|26-850/63|850|224|9.3|23.6|63
Metal|Metal Mesh Heavy|5|26-800/58|800|250|9.5|24.1|58
PET|PET-GG|6|PET-12GG-1800|1800|500|4.3|10.9|61
PET|PET-GG|6|PET-14GG-1600|1600|450|4.9|12.4|61
PET|PET-GG|6|PET-15GG-1400|1400|450|5.4|13.7|57
PET|PET-GG|6|PET-16GG-1320|1320|400|5.8|14.7|59
PET|PET-GG|6|PET-18GG-1180|1180|350|6.5|16.5|60
PET|PET-GG|6|PET-20GG-1000|1000|320|7.6|19.3|58
PET|PET-GG|6|PET-22GG-950|950|300|8|20.3|58
PET|PET-GG|6|PET-23GG-900|900|300|8.3|21.1|56
PET|PET-GG|6|PET-24GG-850|850|300|8.7|22.1|55
PET|PET-GG|6|PET-26GG-800|800|280|9|22.9|55
PET|PET-GG|6|PET-27GG-750|750|280|9.7|24.6|53
PET|PET-GG|6|PET-28GG-710|710|260|10.3|26.2|54
PET|PET-GG|6|PET-30GG-670|670|260|10.7|27.2|52
PET|PET-GG|6|PET-31GG-630|630|240|11.5|29.2|53
PET|PET-GG|6|PET-32GG-600|600|240|12|30.5|51
PET|PET-GG|6|PET-34GG-560|560|240|12.5|31.8|49
PET|PET-GG|6|PET-36GG-530|530|220|13.3|33.8|50
PET|PET-GG|6|PET-38GG-500|500|220|14|35.6|48
PET|PET-GG|6|PET-40GG-475|475|200|15|38.1|49
PET|PET-GG|6|PET-42GG-450|450|200|15.4|39.1|48
PET|PET-GG|6|PET-44GG-425|425|200|16|40.6|46
PET|PET-GG|6|PET-45GG-400|400|180|17.2|43.7|48
PET|PET-GG|6|PET-47GG-375|375|180|18|45.7|46
PET|PET-GG|6|PET-50GG-355|355|160|19.4|49.3|47
PET|PET-GG|6|PET-52GG-335|335|160|20.2|51.3|46
PET|PET-GG|6|PET-54GG-315|315|160|21|53.3|44
PET|PET-GG|6|PET-56GG-300|300|140|22.5|57.2|46
PET|PET-GG|6|PET-60GG-280|280|140|24|61|44
PET|PET-GG|6|PET-64GG-265|265|120|26|66|47
PET|PET-GG|6|PET-66GG-250|250|120|27|68.6|46
PET|PET-GG|6|PET-70GG-236|236|120|28.5|72.4|43
PET|PET-GG|6|PET-72GG-224|224|120|29|73.7|42
PA|PA-HD|8|PA-2HD-350|350|240|17|43|35
PA|PA-HD|8|PA-3HD-300|300|215|19.5|50|34
PA|PA-HD|8|PA-5HD-250|250|180|23|58|34
PA|PA-HD|8|PA-6HD-210|210|140|27.5|70|33
PA|PA-HD|8|PA-8HD-177|177|125|33|84|34
PA|PA-HD|8|PA-9HD-150|150|95|41|104|38
`.trim();

  // PA-Milling and PA-Schlinger publish two yarn/thread-count directions.
  const directionalData = `
PA|PA-Milling|4|PA-3-300|300|100+2·60|100|24|25|61|64|54
PA|PA-Milling|4|PA-4-280|280|100+2·60|100|25.5|26.5|65|67|53
PA|PA-Milling|4|PA-5-250|250|100+2·60|90|27.5|29|70|74|51
PA|PA-Milling|4|PA-6-212|212|90+2·60|80|32|30|81|76|49
PA|PA-Milling|4|PA-7-200|200|80+2·60|80|33.5|35.7|85|91|48
PA|PA-Milling|4|PA-8-180|180|80+2·60|70|35.7|40|91|102|46
PA|PA-Milling|4|PA-8½-160|160|70+2·60|70|40|43.5|102|111|44
PA|PA-Milling|4|PA-9-150|150|70+2·60|60|42.5|47.6|108|121|44
PA|PA-Milling|4|PA-9½-140|140|60+2·60|60|43|50|109|127|43
PA|PA-Milling|4|PA-10-132|132|60+2·50|60|47|52|119|132|44
PA|PA-Milling|4|PA-10½-125|125|60+2·50|60|48.5|54|123|137|40
PA|PA-Milling|4|PA-11-118|118|60+2·50|50|50.5|59.5|128|151|42
PA|PA-Milling|4|PA-12-112|112|60+2·50|50|51.5|61.5|131|156|40
PA|PA-Milling|4|PA-12½-106|106|60+2·43|50|56|64|142|163|38
PA|PA-Milling|4|PA-13-100|100|60+2·50|50|57|66.7|145|169|38
PA|PA-Milling|4|PA-14-95|95|50+2·43|50|61|69|155|175|38
PA|PA-Milling|4|PA-14½-90|90|50+2·43|50|62.5|71.5|159|182|37
PA|PA-Milling|4|PA-15-85|85|50+2·43|50|65|74|165|188|34
PA|PA-Milling|4|PA-17-80|80|50+2·43|44|68|81|173|206|35
PA|PA-Milling|4|PA-20-75|75|50+2·43|44|70|84.7|178|215|34
PA|PA-Milling|4|PA-21-71|71|50+2·39|44|74|87.7|188|223|33
PA|PA-Milling|4|PA-25-63|63|43+2·43|44|78|94|198|239|30
PA|PA-Schlinger|4|PA-7S-200|200|80+2·70S|70|32|37|81|94|50
PA|PA-Schlinger|4|PA-8S-180|180|80+2·60S|70|36|40|91|102|46
PA|PA-Schlinger|4|PA-9S-150|150|70+2·60S|60|41|47|104|119|44
PA|PA-Schlinger|4|PA-9½S-140|140|60+2·60S|60|44|50|112|127|43
PA|PA-Schlinger|4|PA-12S-112|112|60+2·50S|50|52|62|132|157|40
`.trim();

  function number(value) { return Number(value); }

  function squareFabric(parts) {
    const material = parts[0], family = parts[1], page = number(parts[2]), code = parts[3];
    const opening = number(parts[4]), yarn = number(parts[5]);
    const threadsCm = number(parts[6]), threadsIn = number(parts[7]), openArea = number(parts[8]);
    return {
      code, material, family, sourcePage: page, sourceDocument: SOURCE_DOCUMENT,
      openingMicrons: opening, yarnMicrons: yarn, yarnDisplay: String(yarn),
      pitchMicrons: opening + yarn, pitchMicronsOptions: [opening + yarn],
      threadsPerCm: threadsCm, threadCountsPerCm: [threadsCm],
      threadsPerInch: threadsIn, threadCountsPerInch: [threadsIn],
      openAreaPercent: openArea
    };
  }

  function directionalFabric(parts) {
    const material = parts[0], family = parts[1], page = number(parts[2]), code = parts[3];
    const opening = number(parts[4]), yarns = [parts[5], parts[6]];
    const threadsCm = [number(parts[7]), number(parts[8])];
    const threadsIn = [number(parts[9]), number(parts[10])];
    const openArea = number(parts[11]);
    const pitches = threadsCm.map(function (count) { return 10000 / count; });
    return {
      code, material, family, sourcePage: page, sourceDocument: SOURCE_DOCUMENT,
      openingMicrons: opening, yarnMicrons: yarns.join(" / "), yarnDisplay: yarns.join(" / "), yarns,
      pitchMicrons: pitches.reduce(function (sum, value) { return sum + value; }, 0) / pitches.length,
      pitchMicronsOptions: pitches,
      threadsPerCm: threadsCm.join(" / "), threadCountsPerCm: threadsCm,
      threadsPerInch: threadsIn.join(" / "), threadCountsPerInch: threadsIn,
      openAreaPercent: openArea
    };
  }

  const fabrics = squareData.split("\n").map(function (row) { return squareFabric(row.split("|")); })
    .concat(directionalData.split("\n").map(function (row) { return directionalFabric(row.split("|")); }));

  function relativeDifference(value, reference) {
    return Math.abs(value - reference) / Math.max(1, reference);
  }

  function nearestPitch(measuredPitch, item) {
    let bestPitch = item.pitchMicronsOptions[0];
    let bestError = relativeDifference(measuredPitch, bestPitch);
    item.pitchMicronsOptions.slice(1).forEach(function (candidatePitch) {
      const error = relativeDifference(measuredPitch, candidatePitch);
      if (error < bestError) { bestError = error; bestPitch = candidatePitch; }
    });
    return { pitch: bestPitch, error: bestError };
  }

  function matchCandidates(measurement, materialFilter) {
    const measuredPitch = Number(measurement && measurement.pitchMicrons);
    const measuredOpening = Number(measurement && measurement.openingMicrons);
    const material = materialFilter && materialFilter !== "Auto" ? materialFilter : null;
    if (!(measuredPitch > 0)) return [];

    return fabrics
      .filter(function (item) { return !material || item.material === material; })
      .map(function (item) {
        const nearest = nearestPitch(measuredPitch, item);
        const openingError = measuredOpening > 0 ? relativeDifference(measuredOpening, item.openingMicrons) : null;
        const score = openingError === null ? nearest.error : nearest.error * 0.72 + openingError * 0.28;
        return Object.assign({}, item, {
          matchedPitchMicrons: nearest.pitch,
          pitchError: nearest.error,
          openingError: openingError,
          score: score,
          confidence: Math.max(0, Math.min(99, Math.round(100 * (1 - score * 2.3))))
        });
      })
      .sort(function (a, b) {
        if (a.score !== b.score) return a.score - b.score;
        return a.code.localeCompare(b.code);
      });
  }

  function materialArabic(material) {
    return { PA: "بولي أميد PA", PET: "بوليستر PET", Metal: "ستانلس ستيل AISI 304" }[material] || material;
  }

  function familyArabic(family) {
    return {
      "PA-GG": "PA-GG — Plansifter",
      "PA-Milling": "PA-Milling — Plansifter",
      "PA-Schlinger": "PA-Schlinger — Leno weave",
      "PA-XXX": "PA-XXX — Plansifter",
      "PA-MF": "PA-MF — Plansifter",
      "Metal Mesh": "Metal Mesh — Plansifter",
      "Metal Mesh Heavy": "Metal Mesh Heavy — Plansifter",
      "PET-GG": "PET-GG — Purifier",
      "PA-HD": "PA-HD — Centrifugal sifter"
    }[family] || family;
  }

  return {
    sourceDocument: SOURCE_DOCUMENT,
    sourceOnly: true,
    fabrics,
    matchCandidates,
    materialArabic,
    familyArabic
  };
});
