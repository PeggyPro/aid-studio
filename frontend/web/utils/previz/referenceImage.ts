import {fileDataUrl} from './export';
/** Shared intake for home and editor; the server performs authoritative image validation. */
export async function preparePrevizReferenceImage(file:File) {
 if(file.size>5*1024*1024||!['image/png','image/jpeg','image/webp'].includes(file.type))throw new Error('请选择5MB以内的PNG、JPEG或WebP图片');
 let source:Blob=file;
 if(file.type==='image/webp'){
  const bitmap=await createImageBitmap(file);
  try{
   if(bitmap.width>8192||bitmap.height>8192||bitmap.width*bitmap.height>16777216)throw new Error('参考图尺寸过大，请缩小后上传');
   const canvas=document.createElement('canvas');canvas.width=bitmap.width;canvas.height=bitmap.height;
   const context=canvas.getContext('2d');if(!context)throw new Error('无法读取参考图');context.drawImage(bitmap,0,0);
   source=await new Promise<Blob>((resolve,reject)=>canvas.toBlob(blob=>blob?resolve(blob):reject(new Error('图片转换失败')),'image/png'));
  }finally{bitmap.close();}
 }
 if(source.size>5*1024*1024)throw new Error('转换后的参考图超过5MB，请缩小后上传');
 const data=await fileDataUrl(source);return data.slice(data.indexOf(',')+1);
}
