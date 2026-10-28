import { BIOME_DEFAULTS, type BiomeColors } from '@/core/format'
import { cx } from '@/ui/cx'
import styles from './BiomeScene.module.css'

/**
 * The biome's colours as a little landscape: the sky fading into the fog, a
 * hill of grass under a tree of leaves, and a pool of water. A colour the file
 * leaves out is drawn hatched: the world's, or the climate's.
 */
export function BiomeScene({ colors, small = false }: { colors: BiomeColors; small?: boolean }) {
  const paint = (color: string | undefined) => (color ? { background: color } : undefined)
  const unset = (color: string | undefined) => (color ? undefined : styles.unset)
  return (
    <div className={cx(styles.scene, small && styles.small)} role="img" aria-label="Biome colours">
      <div
        className={`${styles.sky} ${unset(colors.sky)}`}
        style={
          colors.sky
            ? { background: `linear-gradient(${colors.sky}, ${colors.fog ?? colors.sky})` }
            : undefined
        }
        data-color={colors.sky ?? ''}
      />
      <div className={`${styles.trunk}`} />
      <div className={`${styles.leaves} ${unset(colors.foliage)}`} style={paint(colors.foliage)} />
      <div className={`${styles.hill} ${unset(colors.grass)}`} style={paint(colors.grass)} />
      <div
        className={`${styles.water}`}
        style={{ background: colors.water ?? BIOME_DEFAULTS.water }}
        data-color={colors.water ?? BIOME_DEFAULTS.water}
      />
    </div>
  )
}
