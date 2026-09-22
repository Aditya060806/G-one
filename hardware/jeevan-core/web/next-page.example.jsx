// Copy CoreExplorer.jsx + layers.json to app/components/.
// In app/layout.jsx, import './components/style.css' once.
// Copy Jeevan-Core.glb to public/. Install three in your Next.js app.
import CoreExplorer from './components/CoreExplorer';

export default function Page() {
  return <CoreExplorer modelUrl="/Jeevan-Core.glb" />;
}
