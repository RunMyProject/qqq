<svelte:head>
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link href="https://fonts.googleapis.com/css2?family=Press+Start+2P&display=swap" rel="stylesheet" />
</svelte:head>

<script lang="ts">
 /*
  * +page.svelte
  * Author: Edoardo Sabatini
  * Date: 2026-03-31
  * This file defines the core game logic for "QQQ", a Svelte-based arcade
  * experience featuring AI integration via Quarkus and Qwen.
  * 
  */

  import { onMount } from 'svelte';

  // ══════════════════════════════════════════════════════════
  // ⚙️  GAME SETTINGS
  // ══════════════════════════════════════════════════════════
  let NUM_EGGS   = $state(7);
  let NUM_MOUTHS = $state(4);
  let lives      = $state(3);
  let score      = $state(0);
  let now        = $state(0);
  let gameState: 'playing' | 'gameover' | 'victory' = $state('playing');

  type Direction = 'right' | 'left' | 'up' | 'down';

  // ══════════════════════════════════════════════════════════
  // 🗺️  MAZE  25 × 13 cells  →  arena 800 × 416 px
  // ══════════════════════════════════════════════════════════
  // Boundaries realigned to PNG: wider corridors for smoother movement.
  const TILE = 32;
  const COLS = 25;
  const ROWS = 13;

  const MAZE: number[][] = [
    // 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4
    [1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1], // 0: Top wall
    [1,1,1,1,1,1,1,1,1,1,1,1,2,1,1,1,1,1,1,1,1,1,1,1,1], // 1: Top portal (c:12)
    [1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1], // 2: Open top corridor
    [1,2,0,1,1,1,1,1,1,1,1,0,0,0,1,1,1,1,1,1,1,1,0,2,1], // 3: High side portals
    [1,1,0,1,1,1,1,1,1,1,1,0,0,0,1,1,1,1,1,1,1,1,0,1,1], // 4: Wall blocks
    [1,1,0,1,1,1,1,1,1,1,1,0,0,0,1,1,1,1,1,1,1,1,0,1,1], // 5: Wall blocks
    [1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1], // 6: Middle open corridor
    [1,1,0,1,1,1,1,1,1,1,1,0,0,0,1,1,1,1,1,1,1,1,0,1,1], // 7: Wall blocks
    [1,1,0,1,1,1,1,1,1,1,1,0,0,0,1,1,1,1,1,1,1,1,0,1,1], // 8: Wall blocks
    [1,2,0,1,1,1,1,1,1,1,1,0,0,0,1,1,1,1,1,1,1,1,0,2,1], // 9: Low side portals
    [1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1], // 10: Open bottom corridor
    [1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1], // 11: Bottom wall
    [1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1], // 12: Bottom wall
  ];

  type Portal = { r: number; c: number; exitDir: Direction };
  const PORTALS: Portal[] = [
    { r: 1, c: 12, exitDir: 'down'  },  // Top
    { r: 3, c: 1,  exitDir: 'right' },  // Top-left
    { r: 3, c: 23, exitDir: 'left'  },  // Top-right
    { r: 9, c: 1,  exitDir: 'right' },  // Bottom-left
    { r: 9, c: 23, exitDir: 'left'  },  // Bottom-right
  ];

  function isWalkable(r: number, c: number): boolean {
    if (r < 0 || r >= ROWS || c < 0 || c >= COLS) return false;
    return MAZE[r][c] === 0 || MAZE[r][c] === 2;
  }

  function getWalkableCells(): { col: number; row: number }[] {
    const out: { col: number; row: number }[] = [];
    for (let r = 0; r < ROWS; r++)
      for (let c = 0; c < COLS; c++)
        if (MAZE[r][c] === 0) out.push({ col: c, row: r });
    return out;
  }

  function pickFreeCell(occupied: Set<string>): { col: number; row: number } {
    const free = getWalkableCells().filter(({ col, row }) => !occupied.has(`${col},${row}`));
    const pick = free.length ? free[Math.floor(Math.random() * free.length)] : { col: 12, row: 6 };
    occupied.add(`${pick.col},${pick.row}`);
    return pick;
  }

  // ══════════════════════════════════════════════════════════
  // 🎯  ENTITY TYPES
  // ══════════════════════════════════════════════════════════
  type Entity = {
    col: number; row: number;
    nextCol: number; nextRow: number;
    dir: Direction;
    nextDir: Direction | null;
    progress: number;
    moving: boolean;
    frame: number; lastAni: number;
    flashUntil: number;
  };
  type Egg = { col: number; row: number; frame: number; lastAni: number; collected: boolean };

  const DDELTA: Record<Direction, { dc: number; dr: number }> = {
    right: { dc:  1, dr:  0 },
    left:  { dc: -1, dr:  0 },
    up:    { dc:  0, dr: -1 },
    down:  { dc:  0, dr:  1 },
  };

  const PLAYER_SPD = 0.0062;
  const MOUTH_SPD  = 0.0040;

  const BIRD_SIZE = 80;
  const BIRD_IMGS: Record<Direction, string> = {
    right: '/assets/quail_right.png', left: '/assets/quail_left.png',
    up:    '/assets/quail_up.png',    down: '/assets/quail_down.png',
  };

  const EGG_W = 45; const EGG_H = 65;
  const CELL_W = 100;
  const SCALE  = 1200 / 400;
  const BTRIM  = 4;
  const RAW = {
    down:  { y: 0,   h: 175 }, up:    { y: 175, h: 262 },
    left:  { y: 437, h: 161 }, right: { y: 598, h: 202 },
  };
  const MC = {
    down:  { sy: (RAW.down.y  + BTRIM) / SCALE, h: (RAW.down.h  - BTRIM*2) / SCALE },
    up:    { sy: (RAW.up.y    + BTRIM) / SCALE, h: (RAW.up.h    - BTRIM*2) / SCALE },
    left:  { sy: (RAW.left.y  + BTRIM) / SCALE, h: (RAW.left.h  - BTRIM*2) / SCALE },
    right: { sy: (RAW.right.y + BTRIM) / SCALE, h: (RAW.right.h - BTRIM*2) / SCALE },
  };

  function mkEnt(col: number, row: number, dir: Direction): Entity {
    return { col, row, nextCol: col, nextRow: row, dir, nextDir: null,
             progress: 0, moving: false, frame: 0, lastAni: 0, flashUntil: 0 };
  }

  let bird:   Entity   = $state(mkEnt(12, 6, 'right'));
  let mouths: Entity[] = $state([]);
  let eggs:   Egg[]    = $state([]);
  let invulnerableUntil = $state(0);

  function initEntities() {
    const occ = new Set<string>();
    const DIRS: Direction[] = ['left', 'right', 'up', 'down'];

    const bp = pickFreeCell(occ);
    bird = mkEnt(bp.col, bp.row, 'right');

    mouths = Array.from({ length: NUM_MOUTHS }, () => {
      const p = pickFreeCell(occ);
      const d = DIRS[Math.floor(Math.random() * 4)];
      return { ...mkEnt(p.col, p.row, d), moving: true };
    });

    eggs = Array.from({ length: NUM_EGGS }, () => {
      const p = pickFreeCell(occ);
      return { col: p.col, row: p.row, frame: 0, lastAni: 0, collected: false };
    });
  }

  function epx(e: Entity): number { return ((e.col + 0.5) + (e.nextCol - e.col) * e.progress) * TILE; }
  function epy(e: Entity): number { return ((e.row + 0.5) + (e.nextRow - e.row) * e.progress) * TILE; }

  function chooseMonsterDir(m: Entity): void {
    const dirs: Direction[] = ['right', 'left', 'up', 'down'];
    for (let i = dirs.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [dirs[i], dirs[j]] = [dirs[j], dirs[i]];
    }
    for (const d of dirs) {
      const { dc, dr } = DDELTA[d];
      if (isWalkable(m.row + dr, m.col + dc)) {
        m.dir = d;
        m.nextCol = m.col + dc; m.nextRow = m.row + dr;
        m.progress = 0; m.moving = true;
        return;
      }
    }
    m.moving = false; 
  }

  function advanceEntity(e: Entity, dt: number, speed: number, isPlayer: boolean, ts: number): void {
    if (!e.moving) return;
    e.progress += speed * dt;
    if (e.progress < 1.0) return;

    e.progress = 0; e.col = e.nextCol; e.row = e.nextRow;
    e.nextCol = e.col; e.nextRow = e.row;

    const pIdx = PORTALS.findIndex(p => p.r === e.row && p.c === e.col);
    if (pIdx >= 0) {
      const others = PORTALS.filter((_, i) => i !== pIdx);
      const tgt = others[Math.floor(Math.random() * others.length)];
      e.col = e.nextCol = tgt.c; e.row = e.nextRow = tgt.r;
      e.dir = tgt.exitDir; e.flashUntil = ts + 400;
    }

    if (e.nextDir) {
      const { dc, dr } = DDELTA[e.nextDir];
      if (isWalkable(e.row + dr, e.col + dc)) {
        e.dir = e.nextDir; e.nextDir = null;
        e.nextCol = e.col + dc; e.nextRow = e.row + dr;
        return;
      }
    }

    const { dc, dr } = DDELTA[e.dir];
    if (isWalkable(e.row + dr, e.col + dc)) {
      if (!isPlayer && Math.random() < 0.20) chooseMonsterDir(e);
      else { e.nextCol = e.col + dc; e.nextRow = e.row + dr; }
    } else {
      if (isPlayer) e.moving = false;
      else chooseMonsterDir(e);
    }
  }

  // ══════════════════════════════════════════════════════════
  // 🤖  QUARKUS / QWEN AI INTEGRATION
  // ══════════════════════════════════════════════════════════
  let qwenActive   = $state(false);
  let qwenLastCall = 0;
  let isFetchingAI = false;
  const QWEN_MS    = 600; // Polling loop MS

  async function fetchQwenMove() {
    if (isFetchingAI) return;
    isFetchingAI = true;
    try {
      // 1. Calculate rounded current position
      const c = Math.round(bird.col);
      const r = Math.round(bird.row);

      // 2. Find which directions are actually free from walls
      const valid_moves = (['up', 'down', 'left', 'right'] as Direction[]).filter(d => {
        const { dc, dr } = DDELTA[d];
        return isWalkable(r + dr, c + dc);
      });

      // 3. Prepare the matrix payload for the AI
      const payload = {
        player: { x: c, y: r },
        enemies: mouths.map(m => ({ x: Math.round(m.col), y: Math.round(m.row) })),
        eggs: eggs.filter(e => !e.collected).map(e => ({ x: e.col, y: e.row })),
        valid_moves: valid_moves
      };

      const res = await fetch('http://localhost:8081/game/next-move', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(1500), 
      });

      const data: { direction: string } = await res.json();
      const d = data.direction?.toLowerCase() as Direction | 'stop';

      // Pass the validated move
      if (['right', 'left', 'up', 'down'].includes(d)) {
        setDir(d as Direction);
      }
    } catch (e) {
      console.warn("AI Loop: waiting for Quarkus...", e);
    } finally {
      isFetchingAI = false;
    }
  }

  function qwenTick(ts: number) {
    if (!qwenActive || gameState !== 'playing') return;
    if (ts - qwenLastCall > QWEN_MS && !isFetchingAI) {
      qwenLastCall = ts;
      fetchQwenMove();
    }
  }

  // ══════════════════════════════════════════════════════════
  // 🔁  GAME LOOP
  // ══════════════════════════════════════════════════════════
  let raf = 0;
  let lastTs = 0;

  function loop(ts: number) {
    if (!lastTs) lastTs = ts;
    const dt = Math.min(ts - lastTs, 50); 
    lastTs = ts;
    now = ts;

    if (gameState === 'playing') {
      advanceEntity(bird, dt, PLAYER_SPD, true, ts);
      if (bird.moving && ts - bird.lastAni > 110) {
        bird.frame = (bird.frame + 1) % 4; bird.lastAni = ts;
      }

      for (const m of mouths) {
        if (!m.moving) chooseMonsterDir(m);
        advanceEntity(m, dt, MOUTH_SPD, false, ts);
        if (ts - m.lastAni > 150) { m.frame = (m.frame + 1) % 4; m.lastAni = ts; }
      }

      const bx = epx(bird); const by = epy(bird);
      for (const e of eggs) {
        if (e.collected) continue;
        if (ts - e.lastAni > 260) { e.frame = (e.frame + 1) % 4; e.lastAni = ts; }
        const ex = (e.col + 0.5) * TILE; const ey = (e.row + 0.5) * TILE;
        if (Math.hypot(bx - ex, by - ey) < TILE * 0.72) {
          e.collected = true; score++;
          if (score >= NUM_EGGS) gameState = 'victory';
        }
      }

      if (ts > invulnerableUntil) {
        for (const m of mouths) {
          if (Math.hypot(bx - epx(m), by - epy(m)) < 22) {
            lives--;
            if (lives <= 0) gameState = 'gameover';
            else {
              invulnerableUntil = ts + 2000;
              const pos = pickFreeCell(new Set(mouths.map(mm => `${mm.col},${mm.row}`)));
              bird.col = bird.nextCol = pos.col; bird.row = bird.nextRow = pos.row;
              bird.progress = 0; bird.moving = false;
            }
            break;
          }
        }
      }

      // AI Polling called every frame (limited by internal QWEN_MS)
      qwenTick(ts); 
    }

    raf = requestAnimationFrame(loop);
  }

  function setDir(d: Direction) {
    if (gameState !== 'playing') return;
    bird.nextDir = d; bird.moving = true;
    if (bird.progress < 0.14) {
      const { dc, dr } = DDELTA[d];
      if (isWalkable(bird.row + dr, bird.col + dc)) {
        bird.dir = d; bird.nextDir = null;
        bird.nextCol = bird.col + dc; bird.nextRow = bird.row + dr;
        bird.progress = 0;
      }
    }
  }

  function handleKeydown(e: KeyboardEvent) {
    if (gameState !== 'playing') return;
    switch (e.key) {
      case 'ArrowUp':    case 'w': case 'W': e.preventDefault(); setDir('up');    break;
      case 'ArrowDown':  case 's': case 'S': e.preventDefault(); setDir('down');  break;
      case 'ArrowLeft':  case 'a': case 'A': e.preventDefault(); setDir('left');  break;
      case 'ArrowRight': case 'd': case 'D': e.preventDefault(); setDir('right'); break;
    }
  }

  function handleReset() {
    lives = 3; score = 0; gameState = 'playing';
    invulnerableUntil = 0; lastTs = 0; isFetchingAI = false;
    initEntities();
  }

  onMount(() => {
    initEntities();
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  });
</script>

<svelte:window onkeydown={handleKeydown} />

<div class="screen">
  <div class="title-block">
    <span class="qqq">QQQ</span>
    <p class="subtitle">Quail Quarkus Qwen VS The Hungry Mouths!</p>
  </div>

  <div class="params-panel">
    <label>🥚 Eggs <input type="number" min="1" max="14" bind:value={NUM_EGGS} onchange={handleReset} /></label>
    <label>👄 Mouths <input type="number" min="1" max="8" bind:value={NUM_MOUTHS} onchange={handleReset} /></label>
    <button class="reset-btn" onclick={handleReset}>⟳ RESET</button>
  </div>

  <div class="hud">
    <div class="hud-item">❤️ {lives}</div>
    <div class="hud-item">🥚 {score}/{NUM_EGGS}</div>
  </div>

  <div class="arena">
    {#if gameState === 'gameover'}
      <div class="overlay gameover">GAME OVER<button onclick={handleReset}>TRY AGAIN</button></div>
    {:else if gameState === 'victory'}
      <div class="overlay victory">YOU WIN! 🎉<button onclick={handleReset}>PLAY AGAIN</button></div>
    {/if}

    {#each eggs as e}
      {#if !e.collected}
        <div class="ent" style:transform="translate3d({(e.col+0.5)*TILE - EGG_W/2}px,{(e.row+0.5)*TILE - EGG_H/2}px,0)">
          <div style="width:{EGG_W}px;height:{EGG_H}px;overflow:hidden;position:relative;">
            <img src="/assets/easter_egg.png" alt="egg" class="egg-glow"
                 style:width="{EGG_W*4}px" style:height="{EGG_H}px"
                 style:transform="translate3d({-(e.frame*EGG_W)}px,0,0)"
                 style="position:absolute;top:0;left:0;max-width:none;" />
          </div>
        </div>
      {/if}
    {/each}

    {#each mouths as m}
      <div class="ent {now < m.flashUntil ? 'tp' : ''}" style:transform="translate3d({epx(m)-CELL_W/2}px,{epy(m)-MC[m.dir].h/2}px,0)">
        <div style="width:{CELL_W-4}px;height:{MC[m.dir].h}px;overflow:hidden;position:absolute;left:2px;">
          <img src="/assets/mouths_to_feed.png" alt="monster" class="mouth-glow"
               style="width:400px;position:absolute;max-width:none;"
               style:left="{-(m.frame*CELL_W)-2}px" style:top="{-MC[m.dir].sy}px" />
        </div>
      </div>
    {/each}

    <div class="ent {now < bird.flashUntil ? 'tp' : ''}"
         style:transform="translate3d({epx(bird)-BIRD_SIZE/2}px,{epy(bird)-BIRD_SIZE/2}px,0)"
         style:opacity={(now < invulnerableUntil && Math.floor(now/150)%2===0) ? 0.18 : 1}>
      <div style="width:{BIRD_SIZE}px;height:{BIRD_SIZE}px;overflow:hidden;position:relative;">
        <img src={BIRD_IMGS[bird.dir]} alt="player" class="bird-glow"
             style:width="{BIRD_SIZE*4}px" style:height="{BIRD_SIZE}px"
             style:transform="translate3d({-(bird.frame*BIRD_SIZE)}px,0,0)" />
      </div>
    </div>
  </div>

  <div class="controls-panel">
    <div class="dpad">
      <div class="dpad-title">PLAYER</div>
      <div class="dpad-row">
        <button class="ghost" disabled></button>
        <button onmousedown={() => setDir('up')} ontouchstart={(e: TouchEvent) => { e.preventDefault(); setDir('up'); }}>↑</button>
        <button class="ghost" disabled></button>
      </div>
      <div class="dpad-row">
        <button onmousedown={() => setDir('left')} ontouchstart={(e: TouchEvent) => { e.preventDefault(); setDir('left'); }}>←</button>
        <button onmousedown={() => setDir('down')} ontouchstart={(e: TouchEvent) => { e.preventDefault(); setDir('down'); }}>↓</button>
        <button onmousedown={() => setDir('right')} ontouchstart={(e: TouchEvent) => { e.preventDefault(); setDir('right'); }}>→</button>
      </div>
    </div>

    <div class="ai-panel">
      <div class="dpad-title">🤖 QWEN AI</div>
      <button class="ai-btn {qwenActive ? 'active' : ''}" onclick={() => { qwenActive = !qwenActive; }}>
        {qwenActive ? '⏹ STOP AI' : '▶ START AI'}
      </button>
      <p class="ai-note">:8081/next-move</p>
      <p class="ai-note">{qwenActive ? (isFetchingAI ? 'Analyzing...' : 'Looping') : 'Idle'}</p>
    </div>
  </div>
</div>

<style>
  /* CSS rules remain unchanged as they use standard technical naming */
  :global(*, *::before, *::after) { box-sizing: border-box; margin: 0; padding: 0; }
  :global(*:focus, *:active)      { outline: none !important; -webkit-tap-highlight-color: transparent; }
  :global(img)                    { user-select: none; -webkit-user-drag: none; }
  :global(body) { background: #000; font-family: 'Press Start 2P', monospace; color: #fff; min-height: 100vh; overflow: hidden; }

  .screen { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: .7rem; padding: .7rem; }
  .screen::after { content: ''; pointer-events: none; position: fixed; inset: 0; background: repeating-linear-gradient(to bottom, transparent 0px, transparent 3px, rgba(0,0,0,.08) 3px, rgba(0,0,0,.08) 4px); z-index: 100; }

  .title-block { text-align: center; }
  .qqq { display: block; font-size: clamp(1.8rem, 4vw, 2.8rem); letter-spacing: .15em; color: #4ec9f7; text-shadow: 0 0 10px #4ec9f7, 0 0 40px #4ec9f7aa; animation: pulse 2.4s ease-in-out infinite; margin-bottom: .5rem; }
  .subtitle { font-size: clamp(.32rem, .9vw, .55rem); color: #555; letter-spacing: .04em; }
  @keyframes pulse { 0%, 100% { text-shadow: 0 0 10px #4ec9f7, 0 0 30px #4ec9f7aa; } 50% { text-shadow: 0 0 22px #4ec9f7, 0 0 70px #4ec9f7cc; } }

  .params-panel { display: flex; align-items: center; gap: 1.1rem; background: #0d0d0d; border: 1px solid #2a2a2a; border-radius: 6px; padding: .45rem 1rem; font-size: .46rem; letter-spacing: 1px; }
  .params-panel label { display: flex; align-items: center; gap: .4rem; color: #888; }
  .params-panel input { width: 40px; background: #000; border: 1px solid #444; color: #fff; font-family: 'Press Start 2P', monospace; font-size: .46rem; padding: 3px; border-radius: 3px; text-align: center; }
  .reset-btn { background: #0d0d0d; border: 1px solid #4ec9f7; color: #4ec9f7; font-family: 'Press Start 2P', monospace; font-size: .4rem; padding: 5px 8px; border-radius: 3px; cursor: pointer; transition: .1s; }
  .reset-btn:hover { background: #4ec9f7; color: #000; }

  .hud { display: flex; gap: 2rem; font-size: .68rem; z-index: 2; }
  .hud-item { background: rgba(0,0,0,.6); padding: 5px 10px; border: 2px solid #333; border-radius: 6px; }

  .arena { 
    background-image: url('/assets/maze_background.svg');
    background-size: 100% 100%; 
    background-repeat: no-repeat; position: relative; width: 800px; height: 416px; max-width: 95vw; border: 2px solid #1a1a1a; border-radius: 4px; overflow: hidden; box-shadow: 0 8px 40px rgba(0,0,0,.9); }
  
  .overlay { position: absolute; inset: 0; background: rgba(0,0,0,.88); display: flex; flex-direction: column; align-items: center; justify-content: center; font-size: 2rem; z-index: 50; text-align: center; gap: 1.1rem; }
  .gameover { color: #ff4e4e; text-shadow: 0 0 20px #ff4e4e; }
  .victory { color: #ffd700; text-shadow: 0 0 20px #ffd700; }
  .overlay button { font-family: 'Press Start 2P'; font-size: .9rem; padding: 10px 20px; cursor: pointer; border: 2px solid #fff; background: #000; color: #fff; transition: .2s; }
  .overlay button:hover { background: #fff; color: #000; }

  .ent { position: absolute; top: 0; left: 0; will-change: transform; }
  .ent img { display: block; image-rendering: pixelated; max-width: none; }
  .tp { filter: brightness(2.2) drop-shadow(0 0 12px #4ec9f7) hue-rotate(90deg); }
  .bird-glow { filter: drop-shadow(0 0 8px #4ec9f799); }
  .mouth-glow { filter: drop-shadow(0 0 8px #ff4e4e99); }
  .egg-glow { filter: drop-shadow(0 0 6px rgba(255,215,0,.85)); }

  .controls-panel { display: flex; gap: 2.5rem; justify-content: center; align-items: flex-start; z-index: 20; }
  .dpad { display: flex; flex-direction: column; align-items: center; gap: .22rem; }
  .dpad-title { font-size: .48rem; margin-bottom: .35rem; letter-spacing: 2px; color: #999; }
  .dpad-row { display: flex; gap: .22rem; }
  .dpad button { width: 52px; height: 52px; background: #111; border: 2px solid #333; font-family: 'Press Start 2P', monospace; font-size: .8rem; cursor: pointer; border-radius: 4px; color: #4ec9f7; transition: .07s; display: flex; align-items: center; justify-content: center; }
  .dpad button:active { background: #4ec9f7; color: #000; box-shadow: 0 0 16px #4ec9f7aa; }
  .dpad button.ghost { visibility: hidden; pointer-events: none; }

  .ai-panel { display: flex; flex-direction: column; align-items: center; gap: .38rem; }
  .ai-btn { font-family: 'Press Start 2P', monospace; font-size: .46rem; padding: 9px 12px; border-radius: 4px; cursor: pointer; border: 2px solid #0f0; color: #0f0; background: #000; transition: .1s; letter-spacing: 1px; }
  .ai-btn:hover { background: rgba(0, 255, 0, 0.2); }
  .ai-btn.active { background: #0f0; color: #000; box-shadow: 0 0 15px rgba(0, 255, 0, 0.5); }
  .ai-note { font-size: .3rem; color: #555; letter-spacing: 1px; }
</style>
