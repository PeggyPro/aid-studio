import React, { useEffect, useRef, useState } from 'react';
import { Alert, Spin, Typography } from 'antd';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { disposeModel, parseModel, type ModelGeometryInfo } from './glbPreview';

export interface PreparedModel {
  thumbnail: File;
  info: ModelGeometryInfo;
}
export default function ModelPreview({
  file,
  onPrepared
}: {
  file: Blob;
  onPrepared?: (value: PreparedModel | null) => void;
}) {
  const host = useRef<HTMLDivElement>(null),
    callback = useRef(onPrepared);
  callback.current = onPrepared;
  const [error, setError] = useState(''),
    [info, setInfo] = useState<ModelGeometryInfo | null>(null);
  useEffect(() => {
    let current = true,
      model: THREE.Object3D | undefined,
      renderer: THREE.WebGLRenderer | undefined;
    let controls: OrbitControls | undefined, observer: ResizeObserver | undefined;
    callback.current?.(null);
    setError('');
    setInfo(null);
    const scene = new THREE.Scene();
    scene.background = new THREE.Color('#eef1f5');
    const camera = new THREE.PerspectiveCamera(38, 1, 0.01, 1000);
    void parseModel(file)
      .then(async (asset) => {
        if (!current || !host.current) {
          disposeModel(asset.model);
          return;
        }
        model = asset.model;
        scene.add(model);
        const center = asset.bounds.getCenter(new THREE.Vector3()),
          size = asset.bounds.getSize(new THREE.Vector3());
        const radius = size.length() / 2,
          distance = (radius / Math.sin(THREE.MathUtils.degToRad(camera.fov / 2))) * 1.15;
        camera.near = Math.max(distance / 10000, 0.00001);
        camera.far = Math.max(distance * 100, 100);
        camera.updateProjectionMatrix();
        camera.position.copy(center).add(new THREE.Vector3(1, 0.65, 1).normalize().multiplyScalar(distance));
        camera.lookAt(center);
        scene.add(new THREE.HemisphereLight(0xffffff, 0x6e7580, 2.5));
        const light = new THREE.DirectionalLight(0xffffff, 3);
        light.position.copy(center).add(new THREE.Vector3(distance, distance, distance));
        scene.add(light);
        renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
        renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
        renderer.outputColorSpace = THREE.SRGBColorSpace;
        host.current.appendChild(renderer.domElement);
        controls = new OrbitControls(camera, renderer.domElement);
        controls.target.copy(center);
        controls.update();
        const render = () => renderer?.render(scene, camera);
        controls.addEventListener('change', render);
        const resize = () => {
          if (!host.current || !renderer) return;
          const width = Math.max(240, host.current.clientWidth);
          renderer.setSize(width, 320);
          camera.aspect = width / 320;
          camera.updateProjectionMatrix();
          render();
        };
        observer = new ResizeObserver(resize);
        observer.observe(host.current);
        resize();
        // 固定方形缩略图只改变相机取景，文件中的模型层级和比例始终保留。
        const aspect = camera.aspect;
        renderer.setSize(512, 512);
        camera.aspect = 1;
        camera.updateProjectionMatrix();
        render();
        const thumbnail = await new Promise<Blob>((resolve, reject) =>
          renderer!.domElement.toBlob(
            (blob) => (blob ? resolve(blob) : reject(new Error('模型缩略图生成失败'))),
            'image/png'
          )
        );
        if (!current) return;
        camera.aspect = aspect;
        resize();
        setInfo(asset.info);
        callback.current?.({
          thumbnail: new File([thumbnail], 'model-preview.png', { type: 'image/png' }),
          info: asset.info
        });
      })
      .catch((reason) => {
        if (current) {
          setError(reason instanceof Error ? reason.message : '模型解析失败，请检查GLB格式');
          callback.current?.(null);
        }
      });
    return () => {
      current = false;
      observer?.disconnect();
      controls?.dispose();
      if (model) disposeModel(model);
      renderer?.dispose();
      renderer?.domElement.remove();
    };
  }, [file]);
  return (
    <div>
      {error ? (
        <Alert type="error" showIcon message={error} />
      ) : !info ? (
        <Spin tip="正在解析模型并生成缩略图">
          <div style={{ height: 40 }} />
        </Spin>
      ) : null}
      <div
        ref={host}
        style={{ minHeight: 320, borderRadius: 8, overflow: 'hidden', background: '#eef1f5' }}
        aria-label="GLB模型预览，可拖动旋转和滚轮缩放"
      />
      {info ? (
        <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
          原始尺寸（文件单位）：
          {[info.width, info.height, info.depth].map((value) => Number(value.toPrecision(5))).join(' × ')} ·{' '}
          {info.meshCount} 个网格 · {info.vertexCount.toLocaleString()} 个顶点
        </Typography.Paragraph>
      ) : null}
    </div>
  );
}
