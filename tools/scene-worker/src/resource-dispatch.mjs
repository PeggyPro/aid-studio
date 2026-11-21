import {inspectLocalResourceDocument} from './resource-document.mjs';
import {inspectLocalResourceImage,inspectLocalResourceFont,inspectLocalResourceSvg} from './resource-image.mjs';
import {inspectLocalResourceMedia} from './resource-media.mjs';

export function inspectResource(input, configuration) {
  if (['pdf','pptx','ppt','docx','doc','html','md'].includes(input.format)) return inspectLocalResourceDocument(input,configuration);
  if (input.format==='font') return inspectLocalResourceFont(input,configuration);
  if (input.format==='svg') return inspectLocalResourceSvg(input,configuration);
  if (['image','psd'].includes(input.format)) return inspectLocalResourceImage(input,configuration);
  if (input.format==='video') return inspectLocalResourceMedia(input,configuration);
  throw new Error('RESOURCE_FORMAT_UNSUPPORTED');
}
