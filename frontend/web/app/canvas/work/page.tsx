import { Suspense } from 'react'
import { CanvasPublicationPage } from '@/components/canvas/CanvasPublicationPage'
import { CaseDetailLoading } from '@/components/case/CaseDetailLayout'
export default function CanvasWorkPage(){return <Suspense fallback={<CaseDetailLoading/>}><CanvasPublicationPage/></Suspense>}
