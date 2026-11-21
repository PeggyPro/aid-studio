import {SaxesParser} from 'saxes';
import {imageSize} from 'image-size';
import {Resvg} from '@resvg/resvg-js';
import {createHash} from 'node:crypto';
import {BuildError} from './errors.mjs';

const fail=(code,message)=>{throw new BuildError(code,message)};
const escape=value=>value.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
function raster(bytes){
  if(!Buffer.isBuffer(bytes)||bytes.length===0||bytes.length>10*1024*1024)fail('RESOURCE_IMAGE_LIMIT','SVG引用图片大小无效');
  const info=imageSize(bytes);if(!['png','jpg','gif','webp','bmp'].includes(info.type)||!info.width||!info.height||info.width*info.height>16_000_000)
    fail('RESOURCE_IMAGE_LIMIT','SVG引用图片类型或尺寸无效');
}
function css(value){
  const decoded=value.replace(/\/\*[\s\S]*?\*\//g,'').replace(/\\([a-f0-9]{1,6})\s?/gi,(_,hex)=>String.fromCodePoint(Math.min(parseInt(hex,16),0x10ffff))).replace(/\\([^\r\n])/g,'$1');
  if(/@import\b/i.test(decoded))fail('RESOURCE_SVG_STYLESHEET_REQUIRED','外部样式须先通过资源服务读取并内联');
  for(const match of decoded.matchAll(/url\s*\(\s*(["']?)(.*?)\1\s*\)/gi))if(!match[2].trim().startsWith('#'))fail('RESOURCE_SVG_STYLE_REFERENCE','SVG样式外部引用未获授权');
}

/** Parses without I/O. Relative image references are resolved against the fetched document URL. */
export function prepareResourceSvg(bytes,baseUrl){
  if(!Buffer.isBuffer(bytes)||bytes.length===0||bytes.length>10*1024*1024)fail('RESOURCE_SVG_LIMIT','SVG大小无效');
  const source=new TextDecoder('utf-8',{fatal:true}).decode(bytes),parser=new SaxesParser({xmlns:true});
  const output=[],references=new Set(),stack=[];let count=0,embeddedBytes=0;
  parser.on('doctype',()=>fail('RESOURCE_SVG_DTD','SVG不允许DTD实体声明'));
  parser.on('processinginstruction',pi=>{if(pi.target==='xml-stylesheet')fail('RESOURCE_SVG_STYLESHEET_REQUIRED','外部样式须先通过资源服务读取并内联')});
  parser.on('error',()=>fail('RESOURCE_SVG_INVALID','SVG结构无效'));
  parser.on('opentag',tag=>{
    if(++count>50000||stack.length>=64||Object.keys(tag.attributes).length>256)fail('RESOURCE_SVG_LIMIT','SVG结构超过处理上限');
    if(count===1&&(tag.local!=='svg'||tag.uri&&tag.uri!=='http://www.w3.org/2000/svg'))fail('RESOURCE_SVG_INVALID','缺少SVG根元素');
    const attributes=[];
    for(const attribute of Object.values(tag.attributes)){
      let value=attribute.value;
      if(attribute.local==='style'||/url\s*\(/i.test(value)||value.includes('\\'))css(value);
      if(attribute.local==='href'&&['image','feImage','use'].includes(tag.local)&&!value.startsWith('#')){
        if(value.startsWith('data:')){
          const match=/^data:image\/(png|jpeg|gif|webp|bmp);base64,([A-Za-z0-9+/]*={0,2})$/i.exec(value);
          if(!match)fail('RESOURCE_SVG_EMBEDDED_TYPE','SVG内嵌图片须为已校验的常见栅格格式');
          const image=Buffer.from(match[2],'base64');embeddedBytes+=image.length;if(embeddedBytes>16*1024*1024)fail('RESOURCE_SVG_LIMIT','内嵌图片总量超限');raster(image);
        }else{
          if(tag.local==='use')fail('RESOURCE_SVG_EXTERNAL_USE','外部SVG组件须先显式导入');
          let uri;try{uri=new URL(value,baseUrl)}catch{fail('RESOURCE_SVG_REFERENCE_INVALID','SVG引用地址无效')}
          if(!['https:','http:'].includes(uri.protocol)||uri.username||uri.password)fail('RESOURCE_SVG_REFERENCE_INVALID','SVG不允许本地文件或带认证身份的引用');
          value=uri.href;references.add(value);if(references.size>16)fail('RESOURCE_SVG_LIMIT','SVG外部图片超过16项');
        }
      }
      attributes.push(`${attribute.name}="${escape(value)}"`);
    }
    stack.push({name:tag.name,local:tag.local,style:''});output.push(`<${tag.name}${attributes.length?' '+attributes.join(' '):''}>`);
  });
  const text=value=>{const top=stack.at(-1);if(top?.local==='style'){top.style+=value;if(top.style.length>1_000_000)fail('RESOURCE_SVG_LIMIT','SVG样式过大')}output.push(escape(value))};
  parser.on('text',text);parser.on('cdata',text);
  parser.on('closetag',tag=>{const top=stack.pop();if(top?.local==='style')css(top.style);output.push(`</${tag.name}>`)});
  parser.write(source).close();if(!count||stack.length)fail('RESOURCE_SVG_INVALID','SVG结构不完整');
  return {svg:output.join(''),references:[...references],sourceSha256:digest(bytes),elementCount:count};
}

/** Invoke in a bounded child process. Resources must already be fetched by the authorized URL service. */
export function renderResourceSvg(bytes,{baseUrl,resources=new Map()}={}){
  const prepared=prepareResourceSvg(bytes,baseUrl);let total=0;
  for(const url of prepared.references){const data=resources.get(url);if(!data)fail('RESOURCE_SVG_REFERENCE_REQUIRED','SVG引用图片尚未取得');raster(data);total+=data.length;if(total>16*1024*1024)fail('RESOURCE_SVG_LIMIT','SVG引用图片总量超限')}
  const probe=new Resvg(prepared.svg,{font:{loadSystemFonts:false},logLevel:'off'}),width=probe.width,height=probe.height;
  if(!Number.isFinite(width)||!Number.isFinite(height)||width<1||height<1||width*height>16_000_000||Math.max(width,height)/Math.min(width,height)>2048)
    fail('RESOURCE_SVG_DIMENSIONS','SVG尺寸超过预览上限');
  const fitTo=width>=height?{mode:'width',value:Math.min(2048,Math.ceil(width))}:{mode:'height',value:Math.min(2048,Math.ceil(height))};
  const renderer=new Resvg(prepared.svg,{font:{loadSystemFonts:true},fitTo,logLevel:'off'});
  for(const url of renderer.imagesToResolve()){const data=resources.get(url);if(!data)fail('RESOURCE_SVG_REFERENCE_REQUIRED','SVG图片引用不完整');renderer.resolveImage(url,data)}
  const image=renderer.render(),png=image.asPng();
  if(image.width>2048||image.height>2048||png.length>16*1024*1024)fail('RESOURCE_SVG_LIMIT','SVG预览超过上限');
  return {png,metadata:{format:'SVG',width,height,previewWidth:image.width,previewHeight:image.height,sourceSha256:prepared.sourceSha256,previewSha256:digest(png),externalImageCount:prepared.references.length,elementCount:prepared.elementCount}};
}
