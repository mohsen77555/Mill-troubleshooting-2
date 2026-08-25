(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.SefarCatalog = api;
})(typeof window !== "undefined" ? window : globalThis, function () {
  "use strict";

  // Values transcribed from the user's SEFAR NYTAL Milling catalogue.
  // The app uses aperture plus yarn/wire diameter (pitch), not a generic
  // "mesh number", because two fabrics can have the same thread count but
  // a different clear opening.
  function fabric(code, material, openingMicrons, yarnMicrons, threadsPerCm, threadsPerInch, openAreaPercent) {
    return {
      code: code,
      material: material,
      openingMicrons: openingMicrons,
      yarnMicrons: yarnMicrons,
      pitchMicrons: openingMicrons + yarnMicrons,
      threadsPerCm: threadsPerCm,
      threadsPerInch: threadsPerInch,
      openAreaPercent: openAreaPercent
    };
  }

  const fabrics = [
    // SEFAR NYTAL PA-GG
    fabric("PA-12GG-1800", "PA", 1800, 500, 4.4, 11.2, 61),
    fabric("PA-14GG-1600", "PA", 1600, 450, 5.0, 12.7, 60),
    fabric("PA-15GG-1400", "PA", 1400, 400, 5.4, 13.7, 59),
    fabric("PA-16GG-1320", "PA", 1320, 400, 5.8, 14.7, 59),
    fabric("PA-17GG-1250", "PA", 1250, 350, 6.1, 15.5, 58),
    fabric("PA-18GG-1180", "PA", 1180, 350, 6.5, 16.5, 59),
    fabric("PA-19GG-1120", "PA", 1120, 350, 6.7, 17.0, 58),
    fabric("PA-20GG-1000", "PA", 1000, 320, 7.5, 19.1, 57),
    fabric("PA-22GG-950", "PA", 950, 300, 8.0, 20.3, 57),
    fabric("PA-23GG-900", "PA", 900, 300, 8.3, 21.1, 56),
    fabric("PA-24GG-850", "PA", 850, 300, 8.7, 22.1, 53),
    fabric("PA-26GG-800", "PA", 800, 280, 9.4, 23.9, 53),
    fabric("PA-27GG-750", "PA", 750, 280, 9.7, 24.6, 52),
    fabric("PA-28GG-710", "PA", 710, 260, 10.5, 26.7, 53),
    fabric("PA-30GG-670", "PA", 670, 260, 10.8, 27.4, 53),
    fabric("PA-31GG-630", "PA", 630, 240, 11.5, 29.2, 53),
    fabric("PA-32GG-600", "PA", 600, 240, 11.9, 30.2, 51),
    fabric("PA-34GG-560", "PA", 560, 240, 12.3, 31.2, 49),
    fabric("PA-36GG-530", "PA", 530, 220, 13.3, 33.8, 50),
    fabric("PA-38GG-500", "PA", 500, 220, 13.7, 34.8, 47),
    fabric("PA-40GG-475", "PA", 475, 200, 15.0, 38.1, 48),
    fabric("PA-42GG-450", "PA", 450, 200, 15.4, 39.1, 48),
    fabric("PA-44GG-425", "PA", 425, 200, 16.0, 40.6, 46),
    fabric("PA-45GG-400", "PA", 400, 180, 16.8, 42.7, 47),
    fabric("PA-47GG-375", "PA", 375, 180, 17.5, 44.5, 47),
    fabric("PA-50GG-355", "PA", 355, 160, 19.4, 49.3, 48),
    fabric("PA-52GG-335", "PA", 335, 160, 20.2, 51.3, 46),
    fabric("PA-54GG-315", "PA", 315, 160, 21.0, 53.3, 44),
    fabric("PA-58GG-300", "PA", 300, 140, 21.7, 55.1, 45),
    fabric("PA-60GG-280", "PA", 280, 140, 23.8, 60.5, 45),
    fabric("PA-62GG-275", "PA", 275, 140, 24.1, 61.2, 44),
    fabric("PA-64GG-265", "PA", 265, 140, 25.0, 63.5, 43),
    fabric("PA-66GG-250", "PA", 250, 120, 26.5, 67.3, 46),
    fabric("PA-68GG-243", "PA", 243, 120, 28.0, 71.1, 44),
    fabric("PA-70GG-236", "PA", 236, 120, 28.0, 71.1, 44),
    fabric("PA-72GG-224", "PA", 224, 120, 29.0, 73.7, 42),
    fabric("PA-74GG-212", "PA", 212, 120, 30.0, 76.2, 40),

    // SEFAR NYTAL PET-GG. Keep it separate: PET fabric can have a similar
    // pitch but not the same catalogue specification as PA.
    fabric("PET-12GG-1800", "PET", 1800, 500, 4.3, 10.9, 61),
    fabric("PET-14GG-1600", "PET", 1600, 450, 4.9, 12.4, 61),
    fabric("PET-15GG-1400", "PET", 1400, 450, 5.4, 13.7, 57),
    fabric("PET-16GG-1320", "PET", 1320, 400, 5.8, 14.7, 59),
    fabric("PET-18GG-1180", "PET", 1180, 350, 6.5, 16.5, 60),
    fabric("PET-20GG-1000", "PET", 1000, 320, 7.6, 19.3, 58),
    fabric("PET-22GG-950", "PET", 950, 300, 8.0, 20.3, 58),
    fabric("PET-23GG-900", "PET", 900, 300, 8.3, 21.1, 56),
    fabric("PET-24GG-850", "PET", 850, 300, 8.7, 22.1, 55),
    fabric("PET-26GG-800", "PET", 800, 280, 9.0, 22.9, 55),
    fabric("PET-27GG-750", "PET", 750, 280, 9.7, 24.6, 53),
    fabric("PET-28GG-710", "PET", 710, 260, 10.3, 26.2, 54),
    fabric("PET-30GG-670", "PET", 670, 260, 10.7, 27.2, 52),
    fabric("PET-31GG-630", "PET", 630, 240, 11.5, 29.2, 53),
    fabric("PET-32GG-600", "PET", 600, 240, 12.0, 30.5, 51),
    fabric("PET-34GG-560", "PET", 560, 240, 12.5, 31.8, 49),
    fabric("PET-36GG-530", "PET", 530, 220, 13.3, 33.8, 50),
    fabric("PET-38GG-500", "PET", 500, 220, 13.8, 35.0, 47),
    fabric("PET-40GG-475", "PET", 475, 200, 14.5, 36.8, 48),
    fabric("PET-42GG-450", "PET", 450, 200, 15.5, 39.4, 46),
    fabric("PET-44GG-425", "PET", 425, 200, 16.0, 40.6, 46),
    fabric("PET-45GG-400", "PET", 400, 180, 16.8, 42.7, 47),
    fabric("PET-47GG-375", "PET", 375, 180, 17.5, 44.5, 47),
    fabric("PET-50GG-355", "PET", 355, 160, 19.4, 49.3, 48),
    fabric("PET-52GG-335", "PET", 335, 160, 20.2, 51.3, 46),
    fabric("PET-54GG-315", "PET", 315, 160, 21.0, 53.3, 44),
    fabric("PET-56GG-300", "PET", 300, 140, 21.7, 55.1, 45),
    fabric("PET-60GG-280", "PET", 280, 140, 23.8, 60.5, 45),
    fabric("PET-64GG-265", "PET", 265, 120, 26.0, 66.0, 47),
    fabric("PET-66GG-250", "PET", 250, 120, 27.0, 68.6, 46),
    fabric("PET-70GG-236", "PET", 236, 120, 28.5, 72.4, 43),
    fabric("PET-72GG-224", "PET", 224, 120, 29.0, 73.7, 42),

    // SEFAR NYTAL Metal Mesh (AISI 304)
    fabric("26-2000/74", "Metal", 2000, 320, 4.3, 10.9, 74.0),
    fabric("26-1800/72", "Metal", 1800, 320, 4.7, 12.0, 72.0),
    fabric("26-1600/77", "Metal", 1600, 220, 5.5, 14.0, 77.0),
    fabric("26-1400/74", "Metal", 1400, 220, 6.2, 15.7, 74.5),
    fabric("26-1250/72", "Metal", 1250, 220, 6.8, 17.3, 72.0),
    fabric("26-1180/71", "Metal", 1180, 220, 7.1, 18.1, 71.0),
    fabric("26-1120/69", "Metal", 1120, 220, 7.5, 19.0, 69.4),
    fabric("26-1060/68", "Metal", 1060, 220, 7.8, 19.8, 68.6),
    fabric("26-1000/67", "Metal", 1000, 220, 8.3, 21.0, 67.0),
    fabric("26-950/68", "Metal", 950, 200, 8.7, 22.0, 68.2),
    fabric("26-900/67", "Metal", 900, 200, 9.1, 23.0, 67.0),
    fabric("26-850/65", "Metal", 850, 200, 9.4, 24.0, 65.5),
    fabric("26-800/64", "Metal", 800, 200, 9.8, 25.0, 64.0),
    fabric("26-750/65", "Metal", 750, 180, 10.6, 27.0, 65.0),
    fabric("26-710/64", "Metal", 710, 180, 11.4, 29.0, 64.0),
    fabric("26-670/65", "Metal", 670, 160, 12.2, 31.0, 65.2),
    fabric("26-630/64", "Metal", 630, 160, 12.6, 32.0, 64.0),
    fabric("26-600/62", "Metal", 600, 160, 13.0, 33.0, 62.3),
    fabric("26-560/60", "Metal", 560, 160, 13.8, 35.0, 60.0),
    fabric("26-530/59", "Metal", 530, 160, 14.6, 37.0, 59.0),
    fabric("26-500/57", "Metal", 500, 160, 15.0, 38.0, 57.6),
    fabric("26-475/56", "Metal", 475, 160, 15.7, 40.0, 56.0),
    fabric("26-450/57", "Metal", 450, 160, 16.9, 43.0, 57.6),
    fabric("26-425/56", "Metal", 425, 140, 17.7, 45.0, 56.6),
    fabric("26-400/54", "Metal", 400, 140, 18.5, 47.0, 54.0),
    fabric("26-375/53", "Metal", 375, 140, 19.3, 49.0, 53.0),
    fabric("26-355/51", "Metal", 355, 140, 20.1, 51.0, 51.0),
    fabric("26-335/49", "Metal", 335, 140, 20.9, 53.0, 49.7),
    fabric("26-315/54", "Metal", 315, 112, 23.2, 59.0, 54.0),
    fabric("26-280/51", "Metal", 280, 112, 25.6, 65.0, 51.0),
    fabric("26-265/53", "Metal", 265, 100, 27.6, 70.0, 52.8),
    fabric("26-250/53", "Metal", 250, 100, 28.7, 73.0, 53.0),
    fabric("26-245/62", "Metal", 245, 65, 32.3, 82.0, 62.0),
    fabric("26-236/51", "Metal", 236, 100, 29.9, 76.0, 51.0),
    fabric("26-224/49", "Metal", 224, 100, 30.7, 78.0, 49.0),
    fabric("26-212/49", "Metal", 212, 90, 33.1, 84.0, 49.0),
    fabric("26-180/45", "Metal", 180, 90, 37.0, 94.0, 45.0),
    fabric("26-160/46", "Metal", 160, 75, 41.0, 105.0, 46.0),
    fabric("26-140/46", "Metal", 140, 67, 47.2, 120.0, 46.0),
    fabric("26-132/49", "Metal", 132, 65, 53.1, 135.0, 49.0),
    fabric("26-125/48", "Metal", 125, 56, 55.1, 140.0, 48.0),
    fabric("26-106/46", "Metal", 106, 50, 64.2, 163.0, 46.0)
  ];

  function relativeDifference(actual, expected) {
    if (!(actual > 0) || !(expected > 0)) return null;
    return Math.abs(actual - expected) / expected;
  }

  function matchCandidates(measurement, materialFilter) {
    const measuredPitch = Number(measurement && measurement.pitchMicrons);
    const measuredOpening = Number(measurement && measurement.openingMicrons);
    const material = materialFilter && materialFilter !== "Auto" ? materialFilter : null;
    if (!(measuredPitch > 0)) return [];

    return fabrics
      .filter(function (item) { return !material || item.material === material; })
      .map(function (item) {
        const pitchError = relativeDifference(measuredPitch, item.pitchMicrons);
        const openingError = measuredOpening > 0 ? relativeDifference(measuredOpening, item.openingMicrons) : null;
        const score = openingError === null ? pitchError : pitchError * 0.72 + openingError * 0.28;
        return Object.assign({}, item, {
          pitchError: pitchError,
          openingError: openingError,
          score: score,
          confidence: Math.max(0, Math.min(99, Math.round(100 * (1 - score * 2.3))))
        });
      })
      .sort(function (a, b) { return a.score - b.score; });
  }

  function materialArabic(material) {
    return { PA: "بولي أميد PA", PET: "بوليستر PET", Metal: "ستانلس ستيل" }[material] || material;
  }

  return {
    fabrics: fabrics,
    matchCandidates: matchCandidates,
    materialArabic: materialArabic
  };
});
