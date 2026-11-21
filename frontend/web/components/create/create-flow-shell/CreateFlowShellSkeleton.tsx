import HomeNewSidebar from '~/components/layout/HomeNewSidebar'

export function CreateFlowShellSkeleton(props: {
  seriesChrome: boolean
  seriesScriptUpload: boolean
  seriesEpisodeList: boolean
  stepCount: number
}) {
  return (
    <div className="main-layout">
      <HomeNewSidebar skeleton />
      <div className="create-main">
        <div className="toolbar create-flow-toolbar toolbar--skeleton">
          <div className="toolbar-left"><div className="skeleton-title skeleton-title-lg" /></div>
        {!props.seriesChrome ? (
          <div className="create-workflow create-workflow--skeleton">
            <div className="create-workflow__scroll flow-rail flow-rail--skeleton">
              {Array.from({ length: props.stepCount }, (_, index) => (
                <div key={index} className="flow-step flow-step--skeleton" />
              ))}
            </div>
          </div>
        ) : null}
          <div className="toolbar-right"><div className="skeleton-btn" /><div className="skeleton-btn skeleton-btn-primary" /></div>
        </div>
        <div className="preview-panel">
          <div
            className={`preview-content${props.seriesScriptUpload ? ' step-series-script-upload' : ''}${props.seriesEpisodeList ? ' step-series-episode-list' : ''}`}
          >
            <div className="skeleton-block" />
          </div>
        </div>
      </div>
    </div>
  )
}
