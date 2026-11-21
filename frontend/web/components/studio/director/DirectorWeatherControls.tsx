import { DIRECTOR_WEATHERS, type DirectorEnvironment, type DirectorWeather } from '@/utils/studio/directorEnvironment'

const WEATHER_LABELS: Record<DirectorWeather, string> = {
  clear: '晴',
  'partly-cloudy': '多云',
  overcast: '阴',
  rain: '雨',
  thunderstorm: '雷雨',
  snow: '雪',
  fog: '雾',
}

function WeatherIcon({ weather }: { weather: DirectorWeather }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      {weather === 'clear' ? <>
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2.5v2m0 15v2M2.5 12h2m15 0h2M5.3 5.3l1.4 1.4m10.6 10.6 1.4 1.4m0-13.4-1.4 1.4M6.7 17.3l-1.4 1.4" />
      </> : <>
        {weather === 'partly-cloudy' && <path d="M6 8a3.5 3.5 0 0 1 6-2M3 5l1 1m4-4v1.5m5-.5-1 1M2 9h1.5" />}
        <path d="M6 16a3.7 3.7 0 0 1-.6-7.3 5.5 5.5 0 0 1 10.5 1.5A3 3 0 1 1 17 16Z" />
        {weather === 'overcast' && <path d="M15 5a4 4 0 0 1 6 3.5" />}
        {weather === 'rain' && <path d="m7 19-1 2m6-2-1 2m6-2-1 2" />}
        {weather === 'thunderstorm' && <path d="m12.8 15-2.4 4h3l-2.4 3" />}
        {weather === 'snow' && <path d="M7 19v2m-1-1h2m4-1v2m-1-1h2m4-1v2m-1-1h2" />}
        {weather === 'fog' && <path d="M5 19h14m-10 3h7" />}
      </>}
    </svg>
  )
}

export function DirectorWeatherControls({ environment, weather = 'clear', onChange }: {
  environment: DirectorEnvironment
  weather?: DirectorWeather
  onChange: (weather: DirectorWeather) => void
}) {
  const studio = environment === 'studio'
  return (
    <div className="director-weather">
      <div className="director-section-title">
        <strong>天气</strong>
        <span className="director-weather__status" aria-live="polite">{studio ? '棚内布光' : WEATHER_LABELS[weather]}</span>
      </div>
      <div className="director-preset-grid director-preset-grid--cards director-weather__options" role="group" aria-label="天气">
        {DIRECTOR_WEATHERS.map(value => {
          const active = !studio && value === weather
          return <button type="button" key={value} aria-pressed={active}
            className={active ? 'is-active' : undefined} onClick={() => { if (!active) onChange(value) }}>
            <WeatherIcon weather={value} />
            <span>{WEATHER_LABELS[value]}</span>
          </button>
        })}
      </div>
      {studio && <p className="director-field__hint">选择天气后切换到室外日光，随后可选夜景。</p>}
    </div>
  )
}
