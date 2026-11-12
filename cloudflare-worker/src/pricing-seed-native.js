export function shouldSeedPricingDefaults(marker, requiredVersion = 7) {
  return !(marker?.applied === true && Number(marker.version) >= requiredVersion);
}
