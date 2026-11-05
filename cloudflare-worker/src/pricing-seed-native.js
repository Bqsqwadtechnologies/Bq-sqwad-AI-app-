export function shouldSeedPricingDefaults(marker, requiredVersion = 5) {
  return !(marker?.applied === true && Number(marker.version) >= requiredVersion);
}
