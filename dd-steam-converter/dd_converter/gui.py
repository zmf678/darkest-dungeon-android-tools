"""Ember-themed desktop GUI for Steam->Android conversion, migration, complete save editing, and content modification."""

from __future__ import annotations

import json
from pathlib import Path
import threading
import tkinter as tk
from tkinter import filedialog, messagebox
from typing import Optional

try:
    import customtkinter as ctk
except ImportError:
    ctk = None

from .adb_push import push_to_device
from .content_modifier import MOD_ID, ModifierConfig, generate_content_mod, pack_mod_zip
from .migration import build_migration_zip
from .pak_builder import BuildOptions, build_pak, verify_pak
from .save_editor import (
    add_trinket,
    batch_improve_heroes,
    list_heroes,
    list_trinkets,
    load_game_catalog,
    read_estate,
    unlock_all_buildings,
    write_estate,
)
from .steam_detect import SteamEnvironment, detect_steam

BG = "#151111"
PANEL = "#211918"
INK = "#F5E6D4"
MUTED = "#C1A897"
GOLD = "#E1AB71"
RED = "#733029"


def main() -> None:
    if ctk is None:
        root = tk.Tk()
        root.title("暗黑地牢 · Steam 转安卓 & 完整修改器")
        root.configure(bg=BG)
        tk.Label(
            root,
            text="未检测到 customtkinter，请运行 pip install customtkinter 或使用 CLI：python run.py --help",
            fg=INK,
            bg=BG,
            padx=24,
            pady=24,
        ).pack()
        root.mainloop()
        return

    ctk.set_appearance_mode("dark")
    app = ConverterApp()
    app.mainloop()


if ctk is not None:
    class ConverterApp(ctk.CTk):
        def __init__(self) -> None:
            super().__init__()
            self.title("暗黑地牢 · Steam 转安卓 & 余烬完整修改器 v2.0")
            self.geometry("980x700")
            self.configure(fg_color=BG)

            self.env: SteamEnvironment = detect_steam()
            self.status_var = tk.StringVar(value="就绪：已自动扫描本机 Steam 环境")

            header = ctk.CTkFrame(self, fg_color=PANEL, corner_radius=12)
            header.pack(fill="x", padx=14, pady=(12, 8))
            ctk.CTkLabel(header, text="暗黑地牢 · 余烬工具箱", font=("Microsoft YaHei UI", 20, "bold"), text_color=INK).pack(
                side="left", padx=16, pady=12
            )
            ctk.CTkLabel(header, textvariable=self.status_var, text_color=GOLD, font=("Microsoft YaHei UI", 12)).pack(
                side="right", padx=16
            )

            self.tabs = ctk.CTkTabview(self, fg_color=PANEL, segmented_button_fg_color=BG,
                                       segmented_button_selected_color=RED, segmented_button_selected_hover_color="#8d3c33")
            self.tabs.pack(fill="both", expand=True, padx=14, pady=(0, 12))

            self.tab_convert = self.tabs.add("Steam 资源转换 (.pak)")
            self.tab_migrate = self.tabs.add("存档与创意工坊迁移")
            self.tab_save = self.tabs.add("完整存档修改器")
            self.tab_mod = self.tabs.add("完整游戏内容修改器")

            self._build_convert_tab()
            self._build_migrate_tab()
            self._build_save_tab()
            self._build_mod_tab()

        def _run_bg(self, label: str, fn) -> None:
            self.status_var.set(label)

            def worker() -> None:
                try:
                    msg = fn()
                    self.after(0, lambda: self.status_var.set(msg or "完成"))
                except Exception as exc:
                    self.after(0, lambda: (self.status_var.set("失败"), messagebox.showerror("错误", str(exc))))

            threading.Thread(target=worker, daemon=True).start()

        def _build_convert_tab(self) -> None:
            f = self.tab_convert
            self.steam_var = tk.StringVar(value=str(self.env.game_dir or ""))
            self.base_apk_var = tk.StringVar(value=r"C:\Users\36134\Downloads\Darkest-Dungeon-v2.2.2-APKVISION.apk")
            self.out_pak_var = tk.StringVar(value=str(Path.cwd() / "dist/dd_assets.pak"))
            self.tex_var = tk.StringVar(value="dds")
            self.aud_var = tk.StringVar(value="auto")

            for lbl, var, pick_dir in (
                ("Steam 游戏目录", self.steam_var, True),
                ("本机基础运行壳 APK (BYO)", self.base_apk_var, False),
                ("输出 dd_assets.pak", self.out_pak_var, False),
            ):
                row = ctk.CTkFrame(f, fg_color="transparent")
                row.pack(fill="x", padx=12, pady=6)
                ctk.CTkLabel(row, text=lbl, width=180, anchor="w", text_color=INK).pack(side="left")
                ctk.CTkEntry(row, textvariable=var).pack(side="left", fill="x", expand=True, padx=8)
                ctk.CTkButton(
                    row,
                    text="浏览",
                    width=70,
                    fg_color=RED,
                    command=lambda v=var, d=pick_dir: self._pick(v, d),
                ).pack(side="right")

            opt_row = ctk.CTkFrame(f, fg_color="transparent")
            opt_row.pack(fill="x", padx=12, pady=8)
            ctk.CTkLabel(opt_row, text="贴图模式:", text_color=INK).pack(side="left", padx=(0, 6))
            ctk.CTkOptionMenu(opt_row, variable=self.tex_var, values=["dds", "png"]).pack(side="left", padx=(0, 16))
            ctk.CTkLabel(opt_row, text="音频策略:", text_color=INK).pack(side="left", padx=(0, 6))
            ctk.CTkOptionMenu(opt_row, variable=self.aud_var, values=["auto", "base", "steam"]).pack(side="left")

            self.convert_log = ctk.CTkTextbox(f, height=280, fg_color=BG, text_color=INK)
            self.convert_log.pack(fill="both", expand=True, padx=12, pady=8)

            btn_row = ctk.CTkFrame(f, fg_color="transparent")
            btn_row.pack(fill="x", padx=12, pady=8)
            ctk.CTkButton(btn_row, text="开始转换并生成 dd_assets.pak", fg_color=RED, command=self._start_convert).pack(
                side="left", padx=(0, 8)
            )
            ctk.CTkButton(btn_row, text="校验 .pak 完整性", command=self._verify_pak).pack(side="left", padx=(0, 8))
            ctk.CTkButton(btn_row, text="ADB 推送到安卓设备", command=self._push_pak).pack(side="left")

        def _pick(self, var: tk.StringVar, is_dir: bool) -> None:
            res = filedialog.askdirectory() if is_dir else filedialog.askopenfilename()
            if res:
                var.set(res)

        def _start_convert(self) -> None:
            def run() -> str:
                res = build_pak(
                    BuildOptions(
                        steam_dir=Path(self.steam_var.get()),
                        base_apk=Path(self.base_apk_var.get()),
                        output_pak=Path(self.out_pak_var.get()),
                        texture_mode=self.tex_var.get(),
                        audio_source=self.aud_var.get(),
                        progress_cb=lambda s, d, t: self.after(
                            0, lambda line=f"[{s}] {d}/{t}\n": self.convert_log.insert("end", line)
                        ),
                    )
                )
                self.after(0, lambda: self.convert_log.insert("end", f"完成: ok={res.ok}, entries={res.total_entries}\n"))
                return f"PAK 构建完成 ({res.total_entries} 文件)"

            self._run_bg("正在转换 Steam 资源…", run)

        def _verify_pak(self) -> None:
            def run() -> str:
                res = verify_pak(Path(self.out_pak_var.get()))
                self.after(
                    0,
                    lambda: self.convert_log.insert(
                        "end",
                        f"校验结果 ok={res.ok}, files={res.total_entries}, csv_rows={res.checked_csv_rows}, missing={len(res.missing_entries)}, mismatch={len(res.size_mismatches)}\n",
                    ),
                )
                return "校验通过" if res.ok else "发现异常条目"

            self._run_bg("正在逐条核验 filelist.csv…", run)

        def _push_pak(self) -> None:
            self._run_bg("正在通过 ADB 推送到设备…", lambda: (push_to_device(pak_path=Path(self.out_pak_var.get())), "ADB 推送成功")[1])

        def _build_migrate_tab(self) -> None:
            f = self.tab_migrate
            self.mig_out_var = tk.StringVar(value=str(Path.cwd() / "dist/steam-migration.zip"))
            row = ctk.CTkFrame(f, fg_color="transparent")
            row.pack(fill="x", padx=12, pady=10)
            ctk.CTkLabel(row, text="输出迁移包:", text_color=INK).pack(side="left", padx=(0, 8))
            ctk.CTkEntry(row, textvariable=self.mig_out_var).pack(side="left", fill="x", expand=True)

            info = ctk.CTkTextbox(f, height=340, fg_color=BG, text_color=INK)
            info.pack(fill="both", expand=True, padx=12, pady=8)
            for p in self.env.save_profiles:
                info.insert("end", f"档位 {p.slot}: {p.estate_name} [{p.game_mode}] · 启用 {len(p.applied_mods)} 个 Mod\n")
            if not self.env.save_profiles:
                info.insert("end", "未自动检测到 Steam 本地存档，请在命令行指定 --steam-root。\n")

            ctk.CTkButton(
                f,
                text="一键打包 Steam 存档与创意工坊 Mod (自动 Steam → mod 转换)",
                fg_color=RED,
                command=lambda: self._run_bg(
                    "正在打包迁移包…",
                    lambda: (
                        lambda s: f"迁移包已生成: {s.output_zip} ({len(s.slots)} 档位, {len(s.mods)} Mod)"
                    )(build_migration_zip(self.env.save_profiles, self.env.mods, Path(self.mig_out_var.get()))),
                ),
            ).pack(pady=10)

        def _build_save_tab(self) -> None:
            f = self.tab_save
            default_prof = str(self.env.save_profiles[0].path) if self.env.save_profiles else ""
            self.save_prof_var = tk.StringVar(value=default_prof)

            row = ctk.CTkFrame(f, fg_color="transparent")
            row.pack(fill="x", padx=12, pady=8)
            ctk.CTkLabel(row, text="存档目录 (profile_N):", text_color=INK).pack(side="left")
            ctk.CTkEntry(row, textvariable=self.save_prof_var).pack(side="left", fill="x", expand=True, padx=8)
            ctk.CTkButton(row, text="选择", width=64, command=lambda: self._pick(self.save_prof_var, True)).pack(side="left", padx=(0, 6))
            ctk.CTkButton(row, text="读取", width=64, fg_color=RED, command=self._load_save_info).pack(side="left")

            self.save_box = ctk.CTkTextbox(f, height=300, fg_color=BG, text_color=INK)
            self.save_box.pack(fill="both", expand=True, padx=12, pady=8)

            actions = ctk.CTkFrame(f, fg_color="transparent")
            actions.pack(fill="x", padx=12, pady=8)
            ctk.CTkButton(actions, text="货币/传家宝拉满", fg_color=RED, command=self._max_wallet).pack(side="left", padx=4)
            ctk.CTkButton(actions, text="全英雄满血满级满装", fg_color=RED, command=self._max_heroes).pack(side="left", padx=4)
            ctk.CTkButton(actions, text="解锁全部城镇建筑", command=self._unlock_buildings).pack(side="left", padx=4)
            ctk.CTkButton(actions, text="添加先祖地图饰品", command=self._add_sample_trinket).pack(side="left", padx=4)

        def _load_save_info(self) -> None:
            p = Path(self.save_prof_var.get())
            est = read_estate(p)
            heroes = list_heroes(p)
            trinkets = list_trinkets(p)
            self.save_box.delete("1.0", "end")
            self.save_box.insert("end", f"庄园: {est['estate_name']} ({est['game_mode']})\n货币: {est['wallet']}\n饰品数: {len(trinkets)}\n\n")
            for h in heroes:
                self.save_box.insert("end", f"#{h.guid} {h.name} ({h.hero_class}) HP={h.hp_fraction*100:.0f}% 压力={h.stress:.0f} XP={h.resolve_xp} 武器={h.weapon_rank+1} 护甲={h.armour_rank+1}\n")

        def _max_wallet(self) -> None:
            p = Path(self.save_prof_var.get())
            write_estate(p, wallet_updates={"gold": 999999, "bust": 999, "portrait": 999, "deed": 999, "crest": 999, "shard": 9999, "blueprint": 20})
            self._load_save_info()
            self.status_var.set("已拉满货币与传家宝（已自动备份原档）")

        def _max_heroes(self) -> None:
            p = Path(self.save_prof_var.get())
            n = batch_improve_heroes(p, max_level_and_gear=True, heal_and_purify=True)
            self._load_save_info()
            self.status_var.set(f"已升级并治愈 {n} 名英雄")

        def _unlock_buildings(self) -> None:
            p = Path(self.save_prof_var.get())
            n = unlock_all_buildings(p)
            self.status_var.set(f"已解锁全部城镇建筑升级（{n} 项）")

        def _add_sample_trinket(self) -> None:
            p = Path(self.save_prof_var.get())
            add_trinket(p, "ancestors_map", 1)
            self._load_save_info()
            self.status_var.set("已添加祖先的地图")

        def _build_mod_tab(self) -> None:
            f = self.tab_mod
            scroll = ctk.CTkScrollableFrame(f, fg_color=BG)
            scroll.pack(fill="both", expand=True, padx=12, pady=8)

            self.m_stack = tk.IntVar(value=5)
            self.m_trinkets = tk.IntVar(value=3)
            self.m_speed = tk.DoubleVar(value=2.0)
            self.m_crit = tk.DoubleVar(value=2.0)
            self.m_virtue = tk.IntVar(value=75)
            self.m_stress = tk.DoubleVar(value=0.5)
            self.m_hp = tk.DoubleVar(value=1.5)
            self.m_dmg = tk.DoubleVar(value=1.5)
            self.m_discount = tk.IntVar(value=50)
            self.m_free_sup = tk.BooleanVar(value=True)
            self.m_hit100 = tk.BooleanVar(value=True)
            self.m_retreat = tk.BooleanVar(value=True)

            fields = [
                ("背包堆叠倍率 (1/2/5/10/99)", self.m_stack),
                ("英雄饰品槽位 (2/3/4)", self.m_trinkets),
                ("战斗速度倍率 (1.0 - 3.0)", self.m_speed),
                ("我方暴击倍率 (1.5 - 3.0)", self.m_crit),
                ("美德概率 % (25 - 100)", self.m_virtue),
                ("受压倍率 (1.0 / 0.5 / 0.0)", self.m_stress),
                ("全英雄生命倍率 (1.0 - 3.0)", self.m_hp),
                ("全英雄伤害倍率 (1.0 - 3.0)", self.m_dmg),
                ("城镇建筑升级折扣 % (0 - 100)", self.m_discount),
            ]
            for label, var in fields:
                r = ctk.CTkFrame(scroll, fg_color="transparent")
                r.pack(fill="x", pady=4)
                ctk.CTkLabel(r, text=label, width=260, anchor="w", text_color=INK).pack(side="left")
                ctk.CTkEntry(r, textvariable=var, width=120).pack(side="left")

            for text, var in (
                ("补给品免费", self.m_free_sup),
                ("解除 95% 命中上限 (100% 命中)", self.m_hit100),
                ("100% 无损撤退", self.m_retreat),
            ):
                ctk.CTkCheckBox(scroll, text=text, variable=var, text_color=INK).pack(anchor="w", pady=4)

            btns = ctk.CTkFrame(f, fg_color="transparent")
            btns.pack(fill="x", padx=12, pady=8)
            ctk.CTkButton(btns, text="生成通用修改器 Mod (含 ZIP)", fg_color=RED, command=lambda: self._gen_mod(False)).pack(side="left", padx=6)
            ctk.CTkButton(btns, text="直接安装到本机 Steam mods/", command=lambda: self._gen_mod(True)).pack(side="left", padx=6)

        def _gen_mod(self, install_steam: bool) -> None:
            cfg = ModifierConfig(
                stack_multiplier=int(self.m_stack.get()),
                free_supplies=bool(self.m_free_sup.get()),
                hero_trinket_slots=int(self.m_trinkets.get()),
                combat_speed_scale=float(self.m_speed.get()),
                player_crit_multiplier=float(self.m_crit.get()),
                always_hit_cap=1.0 if self.m_hit100.get() else 0.95,
                guaranteed_retreat=bool(self.m_retreat.get()),
                virtue_chance_percent=int(self.m_virtue.get()),
                stress_scale=float(self.m_stress.get()),
                building_cost_discount=int(self.m_discount.get()),
                hero_hp_multiplier=float(self.m_hp.get()),
                hero_dmg_multiplier=float(self.m_dmg.get()),
            )
            steam_dir = Path(self.steam_var.get()) if self.steam_var.get() else None
            target = (steam_dir / "mods" / MOD_ID) if (install_steam and steam_dir) else (Path.cwd() / "dist" / MOD_ID)
            generate_content_mod(target, cfg, steam_dir)
            z = pack_mod_zip(target, Path.cwd() / "dist" / f"{MOD_ID}.zip")
            self.status_var.set(f"修改器 Mod 已生成：{target} & {z}")
