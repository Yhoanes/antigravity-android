#!/usr/bin/env python3
"""
web/patch_main.py - Dynamic patcher for web/main.js (Antigravity v2.36.0 Android UI).

Applies:
1. Android Bridge theme sync hooks (window.Android.getTheme / setTheme / setThemeByUser).
2. Original PC AuxPane v2 redesign (Search tabs/files/plugins/subagents/artifacts/tasks)
   and the header Overview floating popover button ([📄] Wtd -> OverviewToggleIconButton) on Mobile & Desktop.
3. Original PC Customizations (Smart Marketplace V3 | Installed), Automations, and Application Tab inside Settings (⚙️).
4. Voice Mode, Microphone, Permissions Selector badge, Orchestrator Model Selection, /sdcard default path, and touch/menu fixes.
5. Global Root ErrorBoundary (__AgRootEB) above SettingsModal & Router.
"""

import os
import sys


def must_replace(content, old, new, label):
    cnt = content.count(old)
    if cnt != 1:
        raise RuntimeError(f"[web-patch-2.36.0] expected count=1 for {label}, got {cnt}")
    print(f"  [OK] {label}")
    return content.replace(old, new, 1)


LOADER_INIT_V3 = (
    'typeof window!=="undefined"&&!window.__agMpSmartV3&&(window.__agMpSmartV3=!0,window.__agLoadMarketplaceCatalog=()=>{'
    'if(window.__agMpCatalog)return Promise.resolve(window.__agMpCatalog);'
    'if(window.__agMpCatalogPromise)return window.__agMpCatalogPromise;'
    'window.__agMpCatalogPromise=(async()=>{'
    'var base={featured:[],search:{customizations:[],groups:[]},details:{}};'
    'try{var r=await fetch("/marketplace_catalog.json");if(r.ok)base=await r.json()}catch(_e){}'
    'try{var ac=typeof AbortController!=="undefined"?new AbortController:null,tid=ac?setTimeout(()=>ac.abort(),3500):null,'
    'r2=await fetch("https://raw.githubusercontent.com/Yhoanes/antigravity-android-plugins/main/catalog.json",ac?{signal:ac.signal}:void 0);'
    'tid&&clearTimeout(tid);'
    'if(r2.ok){var rem=await r2.json();if(rem&&typeof rem==="object"){'
    'var rc=(rem.search&&rem.search.customizations)||[],bc=(base.search&&base.search.customizations)||[],rIds=new Set(rc.map(x=>x.id)),'
    'mc=rc.map(x=>Object.assign({_androidNative:!0},x)).concat(bc.filter(x=>!rIds.has(x.id))),'
    'rg2=(rem.search&&rem.search.groups)||[],bg=(base.search&&base.search.groups)||[],rgIds=new Set(rg2.map(x=>x.id)),'
    'mg=rg2.concat(bg.filter(x=>!rgIds.has(x.id))),'
    'bf=(base.featured||[]).filter(x=>x.id!=="android-featured-hero");'
    'base={schema:1,featured:bf,search:{customizations:mc,groups:mg},details:Object.assign({},base.details||{},rem.details||{})}'
    '}}}catch(_e2){}'
    'window.__agMpCatalog=base;return base})();'
    'return window.__agMpCatalogPromise})'
)

NEW_MP_BLOCK_V3 = (
    'searchMarketplace:async t=>{' + LOADER_INIT_V3 + ';'
    'var _SMR=Wja.file.messages.find(m=>m.name==="SearchMarketplaceResponse")||Wja.file.messages[514],'
    '_q=(t.query??"").trim().toLowerCase(),_gid=t.groupId??"",_pt=t.pageToken??"";'
    'var [_cat,_sr]=await Promise.all([window.__agLoadMarketplaceCatalog().catch(()=>null),_gid==="built-for-android"?Promise.resolve(null):f(v=>v.searchMarketplace(ic(Wja,{query:t.query??"",groupId:_gid,pageToken:_pt}))).catch(()=>null)]);'
    'try{var _allCat=(_cat&&_cat.search&&_cat.search.customizations)||[],_allGrps=(_cat&&_cat.search&&_cat.search.groups)||[],_hasLive=!!(_sr&&_sr.customizations&&_sr.customizations.length>0);'
    'if(_pt&&_hasLive){var _natRaw=_allCat.filter(x=>x._androidNative);if(_natRaw.length>0){var _natMsg=uf(_SMR,{customizations:_natRaw,groups:[]},{ignoreUnknownFields:!0}),_nm=new Map((_natMsg.customizations||[]).map(x=>[x.id,x]));_sr.customizations=_sr.customizations.map(x=>_nm.get(x.id)||x)}return _sr}'
    'var _srcItems=_hasLive?_allCat.filter(x=>x._androidNative):_allCat,_srcGrps=_hasLive?_allGrps.filter(x=>x.id==="built-for-android"||x._androidNative):_allGrps;'
    'if(_gid){var _g=_allGrps.find(x=>x.id===_gid);_srcItems=_g?_srcItems.filter(x=>_g.memberIds&&_g.memberIds.includes(x.id)):[]}'
    'if(_q){_srcItems=_srcItems.filter(x=>(x.displayName||"").toLowerCase().includes(_q)||(x.id||"").toLowerCase().includes(_q)||(x.description||"").toLowerCase().includes(_q))}'
    'var _localMsg=uf(_SMR,{customizations:_srcItems,groups:_q||_gid?[]:_srcGrps},{ignoreUnknownFields:!0});'
    'if(_hasLive){var _fullNatMsg=uf(_SMR,{customizations:_allCat.filter(x=>x._androidNative),groups:[]},{ignoreUnknownFields:!0}),_natById=new Map((_fullNatMsg.customizations||[]).map(x=>[x.id,x])),_seen=new Set((_localMsg.customizations||[]).map(x=>x.id)),_merged=(_localMsg.customizations||[]).slice();'
    'for(var _it of _sr.customizations){var _rep=_natById.get(_it.id)||_it;if(!_seen.has(_rep.id)){_seen.add(_rep.id);_merged.push(_rep)}}'
    'var _gSeen=new Set((_localMsg.groups||[]).map(x=>x.id)),_mGrps=_q||_gid?[]:(_localMsg.groups||[]).slice();'
    'if(!_q&&!_gid&&_sr.groups){for(var _gp of _sr.groups){if(!_gSeen.has(_gp.id)){_gSeen.add(_gp.id);_mGrps.push(_gp)}}}'
    '_localMsg.customizations=_merged;_localMsg.groups=_mGrps;_localMsg.nextPageToken=_sr.nextPageToken||""}'
    'return _localMsg}catch(_e2){return _sr||uf(_SMR,{customizations:[],groups:[]},{ignoreUnknownFields:!0})}},'
    'getMarketplaceCustomization:async t=>{' + LOADER_INIT_V3 + ';'
    'var _GCR=Zja.file.messages.find(m=>m.name==="GetMarketplaceCustomizationResponse")||Zja.file.messages[535],'
    '_cat=await window.__agLoadMarketplaceCatalog().catch(()=>null),_localItem=((_cat&&_cat.search&&_cat.search.customizations)||[]).find(x=>x.id===t.id),_localDet=(_cat&&_cat.details&&_cat.details[t.id])||(_localItem?{customization:_localItem}:null);'
    'if((_localItem&&_localItem._androidNative)||(_localDet&&_localDet.customization&&_localDet.customization._androidNative)){return uf(_GCR,_localDet,{ignoreUnknownFields:!0})}'
    'try{var _gr=await f(v=>v.getMarketplaceCustomization(ic(Zja,{id:t.id,includePreview:t.includePreview??!1})));if(_gr&&_gr.customization)return _gr}catch(_e){}'
    'return uf(_GCR,_localDet||{},{ignoreUnknownFields:!0})},'
    'getMarketplaceBundles:async t=>{var _GBR=Vja.file.messages.find(m=>m.name==="GetMarketplaceBundlesResponse")||Vja.file.messages[512];try{return await f(v=>v.getMarketplaceBundles(ic(Vja,{ids:t.ids??[]})))}catch(_e){return uf(_GBR,{bundles:[],customizations:[]},{ignoreUnknownFields:!0})}},'
    'getMarketplaceFeatured:async()=>{' + LOADER_INIT_V3 + ';'
    'var _GFR=Xja.file.messages.find(m=>m.name==="GetMarketplaceFeaturedResponse")||Xja.file.messages[526];'
    'try{var _fr=await f(t=>t.getMarketplaceFeatured(ic(Xja,{})));if(_fr&&_fr.featured&&_fr.featured.length>0)return _fr}catch(_e){}'
    'try{var _cat=await window.__agLoadMarketplaceCatalog();return uf(_GFR,{featured:((_cat&&_cat.featured)||[]).filter(x=>x.id!=="android-featured-hero"),customizations:(_cat&&_cat.search&&_cat.search.customizations)||[]},{ignoreUnknownFields:!0})}catch(_e2){return uf(_GFR,{featured:[],customizations:[]},{ignoreUnknownFields:!0})}},'
    'getOnboardingPersonas:async()=>f(t=>t.getOnboardingPersonas(ic(Yja,{}))),'
    'installCustomization:async t=>{' + LOADER_INIT_V3 + ';'
    'var _ICR=$ja.file.messages.find(m=>m.name==="InstallCustomizationResponse")||$ja.file.messages[538],'
    '_cat=await window.__agLoadMarketplaceCatalog().catch(()=>null),_c=((_cat&&_cat.search&&_cat.search.customizations)||[]).find(x=>x.id===t.id),_pname=(t.id||"").split("/").pop(),_url=(_c&&_c.source&&_c.source.archive&&_c.source.archive.url)||"",_mp=(_c&&_c.marketplace)||"antigravity-plugins-official",_ver=(_c&&_c.version)||"1.0.0",_isNat=!!(_c&&_c._androidNative)||["agy-video-studio","craft-anything","chrome-devtools-plugin","android-cli-plugin","science"].includes(_pname);'
    'if(_isNat&&window.Android&&window.Android.installMarketplacePlugin){try{var _njr=JSON.parse(window.Android.installMarketplacePlugin(_pname,_url,t.id,_mp,_ver));await new Promise(r=>setTimeout(r,350));return uf(_ICR,_njr,{ignoreUnknownFields:!0})}catch(_ne){}}'
    'try{var _ir=await f(v=>v.installCustomization(ic($ja,{id:t.id,bundleId:t.bundleId})));if(_ir&&_ir.success){try{window.Android&&window.Android.postProcessInstalledPlugin&&window.Android.postProcessInstalledPlugin(_pname)}catch(_pe){}return _ir}}catch(_e){}'
    'try{if(window.Android&&window.Android.installMarketplacePlugin){var _jr=JSON.parse(window.Android.installMarketplacePlugin(_pname,_url,t.id,_mp,_ver));await new Promise(r=>setTimeout(r,350));return uf(_ICR,_jr,{ignoreUnknownFields:!0})}}catch(_e2){return uf(_ICR,{success:!1,message:String(_e2)},{ignoreUnknownFields:!0})}'
    'return uf(_ICR,{success:!1,message:"Marketplace install unavailable"},{ignoreUnknownFields:!0})},'
)


def apply_application_tab_patch(content):
    content = must_replace(
        content,
        'Shortcuts:X$,App:{feature:{visible:!1},applyFirst:[{runtimeGating:{host:[kj.Electron,kj.Web.Desktop.RemoteControl,kj.Web.Mobile.RemoteControl]},feature:{visible:!0}}],important:[W$]}}',
        'Shortcuts:X$,App:{feature:{visible:!0}}}',
        "Dae_App_visible",
    )
    content = must_replace(
        content,
        'xbe={App:{feature:{visible:!1}}}',
        'xbe={App:{feature:{visible:!0}}}',
        "xbe_App_visible",
    )
    content = must_replace(
        content,
        'return F.createElement(aW,{title:"Application",description:`Manage ${b} app settings.`},F.createElement(OW,{screen:"App"})',
        'return F.createElement(aW,{title:"Application",description:`Manage ${b} app settings.`},F.createElement("div",{id:"agy-application-settings-anchor",className:"flex flex-col gap-8 w-full"}),F.createElement(OW,{screen:"App"})',
        "B7b_application_anchor",
    )
    content = must_replace(
        content,
        'var k5b=({title:a="Notifications"})=>{var b=vd().core.notificationService,c=(0,F.useCallback)(async()=>{var d=e,f=e;try{f(await d(b.openPreferences()))}finally{d()}},[b]);',
        'var k5b=({title:a="Notifications"})=>{var b=vd().core.notificationService,c=(0,F.useCallback)(async()=>{try{if(window.Android&&typeof window.Android.openAppSettings==="function"){window.Android.openAppSettings();return}}catch(_e){}var d=e,f=e;try{f(await d(b.openPreferences()))}finally{d()}},[b]);',
        "app_notifications_android_settings",
    )
    return content


def patch_main(file_path):
    if not os.path.isfile(file_path):
        print(f"[web-patch-2.36.0] file not found: {file_path}", file=sys.stderr)
        return False

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    if (
        "Android.getTheme" in content
        and "__agMpSmartV3" in content
        and "__AgRootEB" in content
        and "agy-application-settings-anchor" in content
        and "F.createElement(Wtd,null),q!==void 0" in content
    ):
        print("[web-patch-2.36.0] main.js already patched (v2.36.0 Android UI + Application Tab + Smart Marketplace V3 + RootEB)")
        return True

    # 1a. R0() system theme resolver via window.Android.getTheme()
    content = must_replace(
        content,
        'function R0(){return typeof window!=="undefined"&&window.matchMedia?window.matchMedia("(prefers-color-scheme: dark)").matches:!1}',
        'function R0(){try{if(typeof window!=="undefined"&&window.Android&&typeof window.Android.getTheme==="function"){var _t=window.Android.getTheme();if(_t==="dark")return!0;if(_t==="light")return!1}if(typeof window!=="undefined"&&window.__AG_RESOLVED_THEME__){if(window.__AG_RESOLVED_THEME__==="dark")return!0;if(window.__AG_RESOLVED_THEME__==="light")return!1}}catch(_e){}return typeof window!=="undefined"&&window.matchMedia?window.matchMedia("(prefers-color-scheme: dark)").matches:!1}',
        "R0_system_theme",
    )

    # 1b. l1d(a) hostTheme=system resolver + unconditional startup call
    content = must_replace(
        content,
        'function l1d(a){if(typeof window!=="undefined"){var b=(new URLSearchParams(window.location.search)).get("hostTheme");b!=="dark"&&b!=="light"||Sh.setState(b);',
        'function l1d(a){if(typeof window!=="undefined"){var b=(new URLSearchParams(window.location.search)).get("hostTheme");b==="system"&&(b=typeof window!=="undefined"&&window.Android&&window.Android.getTheme?window.Android.getTheme():(typeof window!=="undefined"?window.__AG_RESOLVED_THEME__:void 0));b!=="dark"&&b!=="light"||Sh.setState(b);',
        "l1d_hostTheme",
    )
    content = must_replace(
        content,
        "d&&l1d(f?.router);",
        "l1d(f?.router);",
        "l1d_startup_call",
    )

    # 1c. Theme sync (window.Android.setTheme, __agOnSystemThemeChanged, setThemeByUser)
    content = must_replace(
        content,
        'A=E=>{m.setState(E);E=S0({hostTheme:b?Sh.getState():void 0,themeMode:E});var H=t.getState();H=E===2?H.light:H.dark;var J=T0(E);B=!0;try{N0(J,H,E,b)}finally{B=!1}l.setState(J);z(J)}',
        'A=E=>{m.setState(E);var _origE=E;E=S0({hostTheme:b?Sh.getState():void 0,themeMode:E});var H=t.getState();H=E===2?H.light:H.dark;var J=T0(E);B=!0;try{N0(J,H,E,b)}finally{B=!1}l.setState(J);try{var _m=J.uiTheme==="vs-dark"?"dark":"light",_thm=b||_origE!==1?_m:"system";window.__agSelectedTheme=_thm;window.Android&&window.Android.setTheme&&window.Android.setTheme(_thm)}catch(_e){}z(J)}',
        "theme_setTheme",
    )
    content = must_replace(
        content,
        ':window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change",()=>{m.getState()===1&&A(1)});',
        ':(c=()=>{if(c._updating)return;c._updating=!0;try{var _cur=window.Android&&window.Android.getTheme?window.Android.getTheme():window.__AG_RESOLVED_THEME__;if((_cur==="dark"||_cur==="light")&&Sh.getState()!==_cur)Sh.setState(_cur)}catch(_e){}try{m.getState()===1&&A(1)}catch(_e){}try{typeof window!=="undefined"&&window.dispatchEvent(typeof CustomEvent==="function"?new CustomEvent("__ag_theme_change"):new Event("__ag_theme_change"))}catch(_e){}c._updating=!1},c._updating=!1,window.__agOnSystemThemeChanged=c,Sh.onDidChange(()=>{c._updating||c()}),window.matchMedia&&window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change",c));',
        "theme_system_listener",
    )
    content = must_replace(
        content,
        "pushUpdate:E=>{switch(E){case 2:E=2;break;case 3:E=1;break;case 1:E=3;break;default:E=1}b1d(a,E)}",
        'pushUpdate:E=>{var _orig=E;switch(E){case 2:E=2;break;case 3:E=1;break;case 1:E=3;break;default:E=1}b1d(a,E);A(_orig);try{var _um=_orig===2?"light":(_orig===3?"dark":"system");window.Android&&window.Android.setThemeByUser&&window.Android.setThemeByUser(_um)}catch(_e){}}',
        "theme_pushUpdate",
    )

    # 2. AuxPane v2 redesign + Overview [📄] popover button (Wtd) on Mobile & Desktop
    content = must_replace(
        content,
        'auxPaneComponent:{feature:{overviewPanelMode:"outputs"}}',
        'auxPaneComponent:{feature:{overviewPanelMode:"full",redesignEnabled:!0}}',
        "auxPane_outputs_to_full",
    )
    content = must_replace(
        content,
        '!b&&F.createElement(F.Fragment,null,\nF.createElement(Fud,{cascadeId:t}),q!==void 0&&(q.kind!=="customView"||r.length>0)&&F.createElement(DY,{iconName:"dock_to_bottom",onClick:()=>{x()},"aria-label":"Toggle Side Panel",dataTestId:"mobile-toggle-aux-sidebar",size:"icon-touch",showAttentionDot:n}))',
        '!b&&F.createElement(F.Fragment,null,\nF.createElement(Fud,{cascadeId:t}),F.createElement(Wtd,null),q!==void 0&&(q.kind!=="customView"||r.length>0)&&F.createElement(DY,{iconName:"dock_to_bottom",onClick:()=>{x()},"aria-label":"Toggle Side Panel",dataTestId:"mobile-toggle-aux-sidebar",size:"icon-touch",showAttentionDot:n}))',
        "mobile_header_Wtd",
    )
    content = must_replace(
        content,
        'n=t?F.createElement(TEd,{content:aa,sidePanel:Z,statusBar:Y}):F.createElement(LFd,',
        'n=t?F.createElement(TEd,{content:E,sidePanel:Z,statusBar:Y}):F.createElement(LFd,',
        "mobile_TEd_with_overview_host",
    )
    content = must_replace(
        content,
        'function krd(a,b){a=b??a;',
        'function krd(a,b){a=a??b;',
        "krd_keep_open_when_count_positive",
    )
    content = must_replace(
        content,
        '{id:"modifiedFiles",title:"Modified Files",defaultEnabled:!1}',
        '{id:"modifiedFiles",title:"Modified Files",defaultEnabled:!0}',
        "enable_modifiedFiles_by_default",
    )
    content = must_replace(
        content,
        '{type:"fileView",id:"open-file",title:"Open File",icon:F.createElement(iz,{name:"draft",size:16}),veKey:"v31",onSelect:({logEvent:a,commandsService:b})=>\n{a("aux_new_tab_open_file_click");b?.executeCommand("openCommandPalette")}}',
        '{type:"fileView",id:"open-file",title:"Open File",icon:F.createElement(iz,{name:"draft",size:16}),veKey:"v31",onSelect:({logEvent:a,openSidePanelTab:b,panelScope:c})=>\n{a("aux_new_tab_open_file_click");b({type:"fileView",fileView:{}},void 0,{panelScope:c,keepOpen:!0,source:"new_tab_card"})}}',
        "open_file_card",
    )
    content = must_replace(
        content,
        'fileExplorerTab:{feature:{isEnabled:!1},',
        'fileExplorerTab:{feature:{isEnabled:!0},',
        "fileExplorerTab_main",
    )

    # 3. Enable Plugins/Marketplace V3 & Automations and wire them inside Settings (⚙️)
    content = must_replace(
        content,
        'plugins:{feature:!1,applyAll:[{runtimeGating:{customFlag:"enable-plugins"},feature:!0}]}',
        'plugins:{feature:!0,applyAll:[{runtimeGating:{customFlag:"enable-plugins"},feature:!0}]}',
        "plugins_feature",
    )
    content = must_replace(
        content,
        'y=Ph(Hj(b,"enable-sidecars"),D=>l&&(m||D));',
        'y=new Oh(!0);',
        "extensibility_y",
    )
    content = must_replace(
        content,
        'showUISidecars:Hj(b,"enable-ui-sidecars"),showDeclaredPermissions:Hj(b,\n"jetski-sidecar-declared-permissions"),',
        'showUISidecars:Rh(!0),showDeclaredPermissions:Hj(b,\n"jetski-sidecar-declared-permissions"),',
        "extensibility_flags",
    )
    content = must_replace(
        content,
        'deletePlugin:async t=>f(async v=>{var w=ic(Jja);w.pluginId=t.pluginId;return v.deletePlugin(w)}),listPluginAuthProviders:async t=>f(v=>v.listPluginAuthProviders(ic(Kja,t??{}))),',
        'deletePlugin:async t=>{try{var _dr=await f(async v=>{var w=ic(Jja);w.pluginId=t.pluginId;return v.deletePlugin(w)});if(_dr&&_dr.success!==!1)return _dr}catch(_e){}try{if(window.Android&&window.Android.uninstallMarketplacePlugin&&window.Android.uninstallMarketplacePlugin(t.pluginId)){await new Promise(r=>setTimeout(r,250));return{success:!0,message:""}}}catch(_e2){}return{success:!1,message:"Failed to delete plugin"}},listPluginAuthProviders:async()=>({providers:[]}),',
        "deletePlugin_and_authProviders_fallback",
    )
    content = must_replace(
        content,
        'searchMarketplace:async t=>f(v=>v.searchMarketplace(ic(Wja,{query:t.query??"",groupId:t.groupId??"",pageToken:t.pageToken??""}))),getMarketplaceCustomization:async t=>\nf(v=>v.getMarketplaceCustomization(ic(Zja,{id:t.id,includePreview:t.includePreview??!1}))),getMarketplaceBundles:async t=>f(v=>v.getMarketplaceBundles(ic(Vja,{ids:t.ids??[]}))),getMarketplaceFeatured:async()=>f(t=>t.getMarketplaceFeatured(ic(Xja,{}))),getOnboardingPersonas:async()=>f(t=>t.getOnboardingPersonas(ic(Yja,{}))),installCustomization:async t=>f(v=>v.installCustomization(ic($ja,{id:t.id,bundleId:t.bundleId}))),',
        NEW_MP_BLOCK_V3,
        "marketplace_rpcs_v3",
    )
    content = must_replace(
        content,
        'function NX(a,b){return b.find(c=>sbc(c.installedFrom,a))}',
        'function NX(a,b){return b.find(c=>sbc(c.installedFrom,a)||(a?.id&&MX(c)===a.id.split("/").pop()))}',
        "NX_match_by_dir_fallback",
    )
    content = must_replace(
        content,
        '["App",{sections:[{title:"General",settings:["keepComputerAwake","runInBackground"]},{advanced:!0,settings:["autoCheckForUpdates"]}]}]]);',
        '["App",{sections:[{title:"General",settings:["keepComputerAwake","runInBackground"]},{advanced:!0,settings:["autoCheckForUpdates"]}]}],["Automations",{sections:[]}]]);',
        "xJb_automations_screen",
    )
    content = must_replace(
        content,
        '{screen:"Customizations"},{screen:"Browser"},{screen:"Developer"},{screen:"Running"},{screen:"Jetski Chat"}]',
        '{screen:"Customizations"},{screen:"Automations"},{screen:"Browser"},{screen:"Developer"},{screen:"Running"},{screen:"Jetski Chat"}]',
        "N7b_screens",
    )
    content = must_replace(
        content,
        'if(!a)return xJb;var b=new Map(xJb);b.delete("Customizations");return b',
        'return xJb',
        "KU_keep_customizations",
    )
    content = must_replace(
        content,
        'a.get("plugins")&&(m=m.filter(n=>n!=="Customizations"));return m',
        'return m',
        "jXb_keep_customizations_in_settings",
    )
    content = must_replace(
        content,
        'var jec=()=>{var {authorities:a,selectedAuthority:b,setSelectedAuthority:c,selectedGeminiDir:d,selectedConfigDir:f}=QX(),g=lk().get("projectsCustomizations")?.showHeadCustomizationsInMainScreen??!1,h=(0,F.useMemo)(()=>g?["file:///google/src/files/head/depot/"]:[],[g]);return a.length===0?F.createElement(aW,{title:"Customizations"},F.createElement("div",{className:"flex items-center gap-2 text-muted-foreground"},F.createElement(EG,{size:14,className:"text-muted-foreground"}),"Connecting to language server...")):\nF.createElement(iec,{key:b,geminiDir:d,configDir:f,workspaceUris:h,tabs:a.map(k=>({id:k.authority,label:k.displayName})),selectedAuthority:b,onSelectAuthority:c})};',
        'var jec=()=>F.createElement(stc,null,F.createElement(XN,null,F.createElement(zqd,{embedded:!0})));',
        "jec_replace_with_marketplace_zqd",
    )
    content = must_replace(
        content,
        'case "Customizations":return F.createElement(jec,null);',
        'case "Customizations":return F.createElement(jec,null);case "Automations":return F.createElement(gJd,{automationPage:!0});',
        "mobile_automations_screen",
    )
    content = must_replace(
        content,
        'F.createElement("div",{style:{display:b==="Customizations"?"block":"none"}},F.createElement(jec,null))',
        'F.createElement("div",{style:{display:b==="Customizations"?"block":"none"}},F.createElement(jec,null)),F.createElement("div",{style:{display:b==="Automations"?"block":"none"}},F.createElement(gJd,{automationPage:!0}))',
        "desktop_settings_automations_screen",
    )
    content = must_replace(
        content,
        'c({to:"/",search:l=>{l={...l};delete l.cascadeId;delete l.focused;',
        'c({to:"/",search:l=>{l={...l};delete l.cascadeId;delete l.focused;delete l.settingsOpen;delete l.settingsScreen;delete l.settingsProjectId;delete l.settingsSection;',
        "QR_close_settings",
    )

    # Dual-mode tqd / zqd inside Settings modal and on /customizations route
    old_tqd = (
        'function tqd({tab:a,onSelect:b,disabledReasons:c={}}){var d=F.useId();return F.createElement("nav",{"aria-label":"Customizations views",className:"agy-pill inline-flex items-center h-8 rounded-lg border border-border bg-secondary/20 p-0.5 gap-0.5 select-none","data-testid":"plugins-tabs"},sqd.map(({tab:f,to:g,label:h})=>{var k=f===a,l=c[f];return l!==void 0?(g=`${d}-${f}`,F.createElement(F.Fragment,{key:f},F.createElement("button",{type:"button","aria-disabled":"true","aria-label":`${h}, ${l}`,"data-tooltip-id":g,\n'
        'className:wj("agy-pill inline-flex items-center justify-center h-full px-2 rounded-md shrink-0 whitespace-nowrap no-underline transition-colors cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-primary","text-secondary-foreground","opacity-50 !cursor-not-allowed"),"data-testid":`plugins-tab-${f}`},h),F.createElement(XK,{id:g,place:"bottom"},l))):F.createElement(bo,{key:f,to:g,activeOptions:{exact:!0},"aria-current":k?"page":void 0,onClick:()=>{k||b(f)},className:wj("agy-pill inline-flex items-center justify-center h-full px-2 rounded-md shrink-0 whitespace-nowrap no-underline transition-colors cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-primary",\n'
        'k?"bg-secondary text-foreground":"text-secondary-foreground hover:text-foreground"),"data-testid":`plugins-tab-${f}`},h)}))}'
    )
    new_tqd = (
        'function tqd({tab:a,onSelect:b,disabledReasons:c={},inRoute:inR=!0}){var d=F.useId();return F.createElement("nav",{"aria-label":"Customizations views",className:"agy-pill inline-flex items-center h-8 rounded-lg border border-border bg-secondary/20 p-0.5 gap-0.5 select-none","data-testid":"plugins-tabs"},sqd.map(({tab:f,to:g,label:h})=>{var k=f===a,l=c[f];if(l!==void 0){g=`${d}-${f}`;return F.createElement(F.Fragment,{key:f},F.createElement("button",{type:"button","aria-disabled":"true","aria-label":`${h}, ${l}`,"data-tooltip-id":g,className:wj("agy-pill inline-flex items-center justify-center h-full px-2 rounded-md shrink-0 whitespace-nowrap no-underline transition-colors cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-primary","text-secondary-foreground","opacity-50 !cursor-not-allowed"),"data-testid":`plugins-tab-${f}`},h),F.createElement(XK,{id:g,place:"bottom"},l))}var cls=wj("agy-pill inline-flex items-center justify-center h-full px-2 rounded-md shrink-0 whitespace-nowrap no-underline transition-colors cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-primary",k?"bg-secondary text-foreground":"text-secondary-foreground hover:text-foreground");return inR?F.createElement(bo,{key:f,to:g,activeOptions:{exact:!0},"aria-current":k?"page":void 0,onClick:()=>{k||b(f)},className:cls,"data-testid":`plugins-tab-${f}`},h):F.createElement("button",{key:f,type:"button","aria-current":k?"page":void 0,onClick:()=>{k||b(f)},className:wj(cls,"border-none bg-transparent"),"data-testid":`plugins-tab-${f}`},h)}))}'
    )
    content = must_replace(content, old_tqd, new_tqd, "tqd_dual_mode")

    old_zqd = (
        'function zqd({MarkdownRenderer:a}={}){U5("v126");var {q:b}=go({strict:!1}),c=On({select:D=>D.location.pathname}),d=lk().get("customizations"),{marketplace:f}=Rpd(),{marketplaceDisabled:g}=VN(),h=f.kind!=="open"||c.startsWith("/customizations/installed")?"installed":"browse";c=d?.pluginSources??"local";var k=T_(),l=c==="remote",[m,n]=(0,F.useState)(()=>b??k.searchQuery),[p,q]=(0,F.useState)(b);p!==b&&(q(b),b!==void 0&&n(b));(0,F.useEffect)(()=>{k.searchQuery=m},[k,m]);d=(d?.rankedMarketplace??!1)&&\n'
        '!l;var [r,t]=(0,F.useState)(()=>k.marketplaceCategory);(0,F.useEffect)(()=>{k.marketplaceCategory=r},[k,r]);c=d?r?.id??"":"";var v=(0,F.useRef)(null),w=(0,F.useCallback)(D=>{t(D);v.current&&(v.current.scrollTop=0)},[]),x=d&&h==="browse",y=F.createElement(rqd,{searchQuery:m,onSearchQueryChange:n,placeholder:"Search customizations...",inputTestId:"customizations-search",chips:x&&r?[{id:"category",label:r.label,removeLabel:"Clear category",onRemove:()=>{w(null)},testId:"marketplace-category-chip"}]:\n'
        '[],onInputClick:()=>{},onClearClick:()=>{}}),{logTabToggle:z,logSearch:A}=S4(),B=gFc(m,800),C=(0,F.useRef)(m);(0,F.useEffect)(()=>{l||B===C.current||(C.current=B)&&A(h,B)},[B,h,l,A]);return F.createElement(yqd,{searchQuery:l?"":m,category:c,onSelectCategory:d?w:void 0,rankedMarketplace:d,enabled:!g},F.createElement("div",{className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},F.createElement("div",{ref:v,className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},\n'
        'F.createElement("div",{className:"sticky top-0 z-20 bg-background"},F.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 pt-8 pb-4 flex flex-col gap-4"},F.createElement("div",{className:"flex flex-wrap items-center justify-between gap-2 sm:gap-4"},F.createElement("h1",{className:"text-xl font-medium text-foreground select-none sm:px-3 m-0"},"Customizations"),F.createElement("div",{className:"flex items-center gap-4"},f.kind!=="hidden"&&F.createElement(tqd,{tab:h,onSelect:z,disabledReasons:f.kind===\n'
        '"disabled"?{browse:f.reason}:void 0}))),d?F.createElement("div",{className:"flex items-center gap-2"},F.createElement("div",{className:"min-w-0 flex-1"},y),x&&F.createElement(uqd,{category:r,onCategoryChange:w})):y)),F.createElement("div",{className:"max-w-4xl w-full mx-auto px-4 sm:px-8 py-6"},F.createElement(zod,{value:m},l?F.createElement(wqd,{tab:h,MarkdownRenderer:a}):F.createElement(Sn,null))))))}'
    )
    new_zqd = (
        'function zqd({MarkdownRenderer:a,embedded:emb=!1}={}){U5("v126");var {q:b}=go({strict:!1}),c=On({select:D=>D.location.pathname}),pMid=F.useContext(Qn),inR=!emb&&pMid!==void 0&&c.startsWith("/customizations"),mdR=a||d5,[locTab,setLocTab]=(0,F.useState)("browse"),[det,setDet]=(0,F.useState)(null),d=lk().get("customizations"),{marketplace:f}=Rpd(),{marketplaceDisabled:g}=VN(),h=f.kind!=="open"?"installed":inR?(c.startsWith("/customizations/installed")?"installed":"browse"):locTab;c=d?.pluginSources??"local";var k=T_(),l=c==="remote",[m,n]=(0,F.useState)(()=>b??k.searchQuery),[p,q]=(0,F.useState)(b);p!==b&&(q(b),b!==void 0&&n(b));(0,F.useEffect)(()=>{k.searchQuery=m},[k,m]);'
        '(0,F.useEffect)(()=>{if(!inR){window.__agCustSelect=setDet;window.__agCustTab=setLocTab;return()=>{if(window.__agCustSelect===setDet)window.__agCustSelect=null;if(window.__agCustTab===setLocTab)window.__agCustTab=null}}},[inR]);'
        'd=(d?.rankedMarketplace??!1)&&!l;var [r,t]=(0,F.useState)(()=>k.marketplaceCategory);(0,F.useEffect)(()=>{k.marketplaceCategory=r},[k,r]);c=d?r?.id??"":"";var v=(0,F.useRef)(null),w=(0,F.useCallback)(D=>{t(D);v.current&&(v.current.scrollTop=0)},[]),x=d&&h==="browse",y=F.createElement(rqd,{searchQuery:m,onSearchQueryChange:n,placeholder:"Search customizations...",inputTestId:"customizations-search",chips:x&&r?[{id:"category",label:r.label,removeLabel:"Clear category",onRemove:()=>{w(null)},testId:"marketplace-category-chip"}]:[],onInputClick:()=>{},onClearClick:()=>{}}),{logTabToggle:z,logSearch:A}=S4(),B=gFc(m,800),C=(0,F.useRef)(m);'
        '(0,F.useEffect)(()=>{l||B===C.current||(C.current=B)&&A(h,B)},[B,h,l,A]);var onTab=tb=>{inR||setLocTab(tb);setDet(null);z(tb)};'
        'if(!inR&&det)return F.createElement("div",{"data-agy-embedded-cust":"1",className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},F.createElement("div",{className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},F.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 py-4"},F.createElement("button",{type:"button",onClick:()=>setDet(null),className:"mb-4 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground bg-transparent border-none cursor-pointer p-0"},F.createElement(iz,{name:"arrow_back",size:16}),"Customizations"),det.bundleId?F.createElement(mod,{bundleId:det.bundleId}):F.createElement(L6c,{installDir:det.installDir,catalogId:det.catalogId,onUninstalled:()=>setDet(null),onDisconnected:()=>setDet(null),MarkdownRenderer:mdR}))));'
        'return F.createElement(yqd,{searchQuery:l?"":h==="browse"?m:"",category:c,onSelectCategory:d?w:void 0,rankedMarketplace:d,enabled:!g},F.createElement("div",{"data-agy-embedded-cust":inR?void 0:"1",className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},F.createElement("div",{ref:v,className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},F.createElement("div",{className:"sticky top-0 z-20 bg-background"},F.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 pt-6 pb-4 flex flex-col gap-4"},F.createElement("div",{className:"flex flex-wrap items-center justify-between gap-2 sm:gap-4"},F.createElement("h1",{className:"text-xl font-medium text-foreground select-none sm:px-3 m-0"},"Customizations"),F.createElement("div",{className:"flex items-center gap-4"},f.kind!=="hidden"&&F.createElement(tqd,{tab:h,onSelect:onTab,disabledReasons:f.kind==="disabled"?{browse:f.reason}:void 0,inRoute:inR}))),d?F.createElement("div",{className:"flex items-center gap-2"},F.createElement("div",{className:"min-w-0 flex-1"},y),x&&F.createElement(uqd,{category:r,onCategoryChange:w})):y)),F.createElement("div",{className:"max-w-4xl w-full mx-auto px-4 sm:px-8 py-6"},F.createElement(zod,{value:m},l?F.createElement(wqd,{tab:h,MarkdownRenderer:mdR}):inR?F.createElement(Sn,null):(h==="browse"?F.createElement(oqd,{MarkdownRenderer:mdR}):F.createElement(Xpd,{MarkdownRenderer:mdR})))))))}'
    )
    content = must_replace(content, old_zqd, new_zqd, "zqd_dual_mode")

    content = must_replace(
        content,
        'g=Bl(t=>t.newConvoResourceSelector.selectedEnvironmentByProject),h=c.get("currentWorkspace")?.workspaceUri??void 0,k=a||b?.project?.id;a=(0,F.useMemo)(()=>k?d.getProjectStateProvider(k):void 0,[k,d]);a=El(a);var l=k?g[k]:void 0',
        'g=Bl(t=>t.newConvoResourceSelector?.selectedEnvironmentByProject),h=c.get("currentWorkspace")?.workspaceUri??void 0,k=a||b?.project?.id;a=(0,F.useMemo)(()=>k?d.getProjectStateProvider(k):void 0,[k,d]);a=El(a);var l=k&&g?g[k]:void 0',
        "safe_redux_selectedEnvironmentByProject",
    )

    content = must_replace(
        content,
        'return On({select:c=>{c=c.matches.find(d=>a.from?a.from===d.routeId:d.id===b);Rn(!((a.shouldThrow??!0)&&!c),`Could not find ${a.from?`an active match from "${a.from}"`:"a nearest match!"}`);if(c!==void 0)return a.select?a.select(c):c},structuralSharing:a.structuralSharing})}',
        'return On({select:c=>{c=c.matches.find(d=>a.from?a.from===d.routeId:d.id===b)||c.matches[c.matches.length-1]||{params:{},_strictParams:{},search:{},_strictSearch:{}};if(c!==void 0)return a.select?a.select(c):c},structuralSharing:a.structuralSharing})}',
        "route_match_fallback_outside_outlet",
    )

    content = must_replace(
        content,
        'F.createElement(bo,{to:d,params:f,search:g,onClick:m,"aria-label":p,"aria-describedby":y,className:"rounded-sm after:absolute after:inset-0 after:rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary text-left"}',
        'F.createElement(bo,{to:d,params:f,search:g,onClick:ev=>{m?.(ev);if(typeof window!=="undefined"&&window.__agCustSelect&&ev?.currentTarget?.closest?.(\'[data-agy-embedded-cust]\')){ev.preventDefault();window.__agCustSelect({installDir:d==="/customizations/plugins/$pluginId"?f?.pluginId:void 0,catalogId:g?.id||(f?eN(f):void 0)})}},"aria-label":p,"aria-describedby":y,className:"rounded-sm after:absolute after:inset-0 after:rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary text-left"}',
        "P4_card_detail_in_settings",
    )

    content = must_replace(
        content,
        'F.createElement(bo,{to:"/customizations",search:c?{q:b}:void 0,className:g,"data-testid":"marketplace-invitation"},h)',
        'F.createElement(bo,{to:"/customizations",search:c?{q:b}:void 0,onClick:ev=>{if(typeof window!=="undefined"&&window.__agCustTab&&ev?.currentTarget?.closest?.(\'[data-agy-embedded-cust]\')){ev.preventDefault();window.__agCustTab("browse")}},className:g,"data-testid":"marketplace-invitation"},h)',
        "browse_in_settings",
    )

    content = must_replace(
        content,
        'var o0=()=>{var {getActiveEditor:a}=(0,F.useContext)(sP),{logEvent:b}=kN(),c=QR();return(0,F.useCallback)((d,f)=>{b("AGENT_MANAGER_HOME");c({project:d,clearProject:f?.clearProject});queueMicrotask(()=>{var g=a();g&&document.hasFocus()&&bB(g)})},[a,b,c])};',
        'var o0=()=>{var {getActiveEditor:a}=(0,F.useContext)(sP),{logEvent:b}=kN(),c=QR(),hideS=lo();return(0,F.useCallback)((d,f)=>{hideS?.();b("AGENT_MANAGER_HOME");c({project:d,clearProject:f?.clearProject});queueMicrotask(()=>{var g=a();g&&document.hasFocus()&&bB(g)})},[a,b,c,hideS])};',
        "o0_close_settings_on_prompt",
    )

    content = must_replace(
        content,
        'KPb=()=>{var a=co(),b=zPb();return(0,F.useCallback)(c=>{var d=b();a({to:"/sidecars/$sidecarId",params:{sidecarId:c},...(d?{search:d}:{})})},[a,b])}',
        'KPb=()=>{var a=co(),b=zPb(),hideS=lo();return(0,F.useCallback)(c=>{hideS?.();var d=b();a({to:"/sidecars/$sidecarId",params:{sidecarId:c},...(d?{search:d}:{})})},[a,b,hideS])}',
        "KPb_close_settings",
    )

    content = must_replace(
        content,
        'MPb=()=>{var a=co(),b=zPb();return(0,F.useCallback)(c=>{var d=b();a({to:"/sidecars/triggers/$triggerId",params:{triggerId:c},...(d?{search:d}:{})})},[a,b])}',
        'MPb=()=>{var a=co(),b=zPb(),hideS=lo();return(0,F.useCallback)(c=>{hideS?.();var d=b();a({to:"/sidecars/triggers/$triggerId",params:{triggerId:c},...(d?{search:d}:{})})},[a,b,hideS])}',
        "MPb_close_settings",
    )

    content = must_replace(
        content,
        'k=b?HZd(b):"/",l=f(await d(g({initialPath:k,',
        'k=b?HZd(b):"/sdcard",l=f(await d(g({initialPath:k,',
        "default_sdcard_path",
    )

    # 4. Enable Voice Mode, Microphone, Permissions Selector, Orchestrator Model Selection, and touch/menu fixes
    content = must_replace(
        content,
        'inputComponent:{feature:{hideAgentSelectorIfEmpty:!1,hideInSubagentConversation:!1,showPermissionsSelector:!1,disableInlineCompletion:!0,showInternalModesDropdown:!1,enableMicrophone:f,showModelUsage:!0,showModelSelectorUpgradePrompts:!1,enableFileSystemCategoriesInMentions:!1,\nenableGoogleWorkspaceMentions:!1,enableVoiceRelay:!1,enablePasteTextAsSnippet:!1,enableHelpCommand:!1',
        'inputComponent:{feature:{hideAgentSelectorIfEmpty:!1,hideInSubagentConversation:!1,showPermissionsSelector:!0,disableInlineCompletion:!0,showInternalModesDropdown:!1,enableMicrophone:!0,showModelUsage:!0,showModelSelectorUpgradePrompts:!1,enableFileSystemCategoriesInMentions:!1,\nenableGoogleWorkspaceMentions:!1,enableVoiceRelay:!0,enablePasteTextAsSnippet:!0,enableHelpCommand:!1',
        "inputComponent_full",
    )
    content = must_replace(
        content,
        '{runtimeGating:{host:[kj.Web.Desktop,\nkj.Electron],customFlag:"enable-permissions-selector"},feature:{showPermissionsSelector:!0}}',
        '{runtimeGating:{customFlag:"enable-permissions-selector"},feature:{showPermissionsSelector:!0}}',
        "showPermissionsSelector_gating",
    )
    content = must_replace(
        content,
        'inputComponent:{feature:{disableInlineCompletion:!0,showInternalModesDropdown:!1,enableMicrophone:!1,enableVoiceRelay:!1},applyAll:',
        'inputComponent:{feature:{disableInlineCompletion:!0,showInternalModesDropdown:!1,showPermissionsSelector:!0,enableMicrophone:!0,enableVoiceRelay:!0},applyAll:',
        "inputComponent_short",
    )
    content = must_replace(
        content,
        'remoteConversationCapabilities:{feature:{fork:!1,conversationMentions:!1,pluginMentions:!1,sideQuestion:!1,subagentMessaging:!1,revealInFileManager:!1,extensions:!1,connectors:!1,terminal:!1,browserSubagent:!1,localPaths:!1,voice:!1}',
        'remoteConversationCapabilities:{feature:{fork:!1,conversationMentions:!1,pluginMentions:!1,sideQuestion:!1,subagentMessaging:!1,revealInFileManager:!1,extensions:!1,connectors:!1,terminal:!0,browserSubagent:!1,localPaths:!1,voice:!0}',
        "remoteConversationCapabilities",
    )
    content = must_replace(
        content,
        'ib=[...(P9()?["enable-developer-mode"]:[])',
        'ib=["enable-voice-relay","enable-voice-remote","enable-permissions-selector","enable-paste-text-as-snippet","jetski-autonomous-mode","enable-orchestrator-model-selection",...(P9()?["enable-developer-mode"]:[])',
        "ib_customFlags",
    )
    content = must_replace(
        content,
        'let HC={isAvailable:!1,voiceState:"off",voiceConvId:null,muted:!1,liveLines:CC,speed:1};',
        'let HC={isAvailable:!0,voiceState:"off",voiceConvId:null,muted:!1,liveLines:CC,speed:1};',
        "HC_voice_isAvailable",
    )
    content = must_replace(
        content,
        'some(k=>k.kind==="audioinput");b(h)}else b(!1)',
        'some(k=>k.kind==="audioinput");b(!0)}else b(!0)',
        "hasMicrophone_always_true",
    )
    content = must_replace(
        content,
        'enabled:Oj()?Hj(a,"enable-remote-control-audio-transcription"):Hj(a,"enable-audio-transcription")}',
        'enabled:Rh(!0)}',
        "audioTranscription_enabled",
    )
    content = must_replace(
        content,
        'var J=!n&&c;v=(x||v)&&w;w=a&&!c&&!z?.isOn;var I=b??a;a=q==="idle"&&!n&&!D;f=f||l||m||p;l=!!z&&!z.isOn&&\n!f&&!c&&a;',
        'var J=!n&&c;v=!!w;w=a&&!c&&!z?.isOn;var I=b??a;a=q==="idle"&&!D;f=f||l||m||p;l=!!z&&!z.isOn&&\n!f&&!c&&a;',
        "micAndVoice_idle",
    )
    content = must_replace(
        content,
        "Jc=Skd({cascadeId:O,planModel:A?.value,enabled:!ta&&Nh&&(ma??MXb).voice&&(!ub||!!O)})",
        "Jc=Skd({cascadeId:O,planModel:A?.value||1018,enabled:!ta&&Nh&&(ma??MXb).voice&&(!ub||!!O)})",
        "Skd_planModel_fallback",
    )
    content = must_replace(
        content,
        '"shrink-0 whitespace-nowrap @max-[400px]/input-footer:w-7 @max-[400px]/input-footer:justify-center @max-[400px]/input-footer:!p-1.5",I&&"!cursor-not-allowed opacity-50 hover:!bg-transparent")},\nF.createElement(eid,{preset:c,className:"@max-[400px]/input-footer:!h-4 @max-[400px]/input-footer:!w-4"}),F.createElement("span",{ref:L,className:"@max-[400px]/input-footer:hidden"},z),!I&&F.createElement(iz,{name:"keyboard_arrow_down",size:14,className:"shrink-0 @max-[400px]/input-footer:hidden"})',
        '"shrink-0 whitespace-nowrap",I&&"!cursor-not-allowed opacity-50 hover:!bg-transparent")},F.createElement(eid,{preset:c,className:""}),F.createElement("span",{ref:L,className:""},z),!I&&F.createElement(iz,{name:"keyboard_arrow_down",size:14,className:"shrink-0"})',
        "mobilePermissionsBadge",
    )

    # 5. Touch & Marketplace refresh fixes
    content = must_replace(
        content,
        "Mh=Sf=>{var Fi=tb.current?.getRootElement();Fi&&document.activeElement===Fi&&!Fi.contains(Sf.target)&&Sf.preventDefault()}",
        'Mh=Sf=>{var Fi=tb.current?.getRootElement();Fi&&document.activeElement===Fi&&!Fi.contains(Sf.target)&&!(Sf.target instanceof Element&&Sf.target.closest(\'button,[role="button"],[aria-haspopup],[data-testid="project-selector-trigger"],[data-testid="model-selector-trigger"]\'))&&Sf.preventDefault()}',
        "pointerdown_allow_menu_triggers",
    )
    content = must_replace(
        content,
        'return{onPointerDown(x){p.current=x.pointerType},\nonMouseDown(x){var y=p.current,z=x.nativeEvent,A=m.select("open");if(!(x.button!==0||d==="click"||fI(y,!0)&&g)){var B=w(A,x.currentTarget,D=>D==="click"||D==="mousedown");A=YH(z);if(bI(A))v(B,z,A,y);else{var C=x.currentTarget;q.request(()=>{v(B,z,C,y)})}}},onClick(x){if(d!=="mousedown-only"){var y=p.current;if(d==="mousedown"&&y)p.current=void 0;else if(!fI(y,!0)||!g){var z=m.select("open");z=w(z,x.currentTarget,A=>A==="click"||A==="mousedown"||A==="keydown"||A==="keyup");v(z,x.nativeEvent,x.currentTarget,\ny)}}},onKeyDown(){p.current=void 0}}',
        'return{onPointerDown(x){p.current=x.pointerType;p.md=!1},\nonMouseDown(x){var y=p.current,z=x.nativeEvent,A=m.select("open");if(!(x.button!==0||d==="click"||fI(y,!0)&&g)){p.md=!0;var B=w(A,x.currentTarget,D=>D==="click"||D==="mousedown");A=YH(z);if(bI(A))v(B,z,A,y);else{var C=x.currentTarget;q.request(()=>{v(B,z,C,y)})}}},onClick(x){if(d!=="mousedown-only"){var y=p.current;if(d==="mousedown"&&y&&p.md)p.current=void 0,p.md=!1;else if(p.current=void 0,p.md=!1,!fI(y,!0)||!g){var z=m.select("open");z=w(z,x.currentTarget,A=>A==="click"||A==="mousedown"||A==="keydown"||A==="keyup");v(z,x.nativeEvent,x.currentTarget,\ny)}}},onKeyDown(){p.current=void 0;p.md=!1}}',
        "BaseUI_click_fallback_when_mousedown_prevented",
    )
    content = must_replace(
        content,
        "async function R_(a){var b=e,c=e;try{a.isStreaming()?c(await b(a.refreshSkills())):c(await b(a.refresh()))}finally{b()}}",
        "async function R_(a){var b=e,c=e;try{c(await b(a.refresh()))}finally{b()}}",
        "R__always_refresh_plugins_and_skills",
    )

    # 6. Global Root ErrorBoundary (__AgRootEB) above SettingsModal & Router to prevent #root unmount gray screens
    old_root_render = (
        '((w=h)=>{n.render(F.createElement(Fxa.Provider,{value:f},F.createElement(pKd,{...t,featureManagerV2:w,routingConfig:m,updateService:g.updateService,fileSystemProviders:g.fileSystemProviders,MermaidDiagramComponent:jce,WebEffects:g.WebEffects,diagnosticsService:g.diagnosticsService,settingsSyncedState:g.settingsSyncedState})))})();'
    )
    new_root_render = (
        'var __AgRootEB=class extends F.Component{constructor(pr){super(pr);this.state={err:null,retries:0}}static getDerivedStateFromError(er){return{err:er||Error("UI render error")}}componentDidCatch(er,inf){try{console.error("[AGY RootErrorBoundary]",er,inf&&inf.componentStack)}catch(_e){}if(this.state.retries<1){var _s=this;setTimeout(function(){try{if(window.history&&window.location&&window.location.search.indexOf("settingsOpen")!==-1){var _u=new URL(window.location.href);_u.searchParams.delete("settingsOpen");_u.searchParams.delete("settingsScreen");window.history.replaceState(window.history.state,"",_u.pathname+_u.search+_u.hash)}}catch(_e2){}_s.setState(function(st){return{err:null,retries:st.retries+1}})},60)}}render(){if(!this.state.err)return this.props.children;if(this.state.retries<1)return null;var _msg=String(this.state.err&&(this.state.err.message||this.state.err)||"Unexpected UI error");return F.createElement("div",{"data-testid":"agy-root-error-fallback",style:{display:"flex",flexDirection:"column",alignItems:"center",justifyContent:"center",height:"100vh",width:"100vw",padding:"24px",boxSizing:"border-box",background:"var(--background,#101010)",color:"var(--foreground,#f4f4f5)",fontFamily:"system-ui,-apple-system,sans-serif",textAlign:"center",gap:"12px"}},F.createElement("div",{style:{fontSize:"16px",fontWeight:600}},"Interface recovered from a view error"),F.createElement("div",{style:{fontSize:"12px",opacity:.72,maxWidth:"440px",wordBreak:"break-word"}},_msg),F.createElement("div",{style:{display:"flex",gap:"10px",marginTop:"6px"}},F.createElement("button",{type:"button",onClick:()=>this.setState({err:null}),style:{padding:"8px 14px",borderRadius:"8px",border:"1px solid rgba(128,128,128,0.35)",background:"transparent",color:"inherit",fontSize:"13px",cursor:"pointer"}},"Retry View"),F.createElement("button",{type:"button",onClick:()=>{try{window.location.reload()}catch(_e){}},style:{padding:"8px 14px",borderRadius:"8px",border:"none",background:"#3b82f6",color:"#fff",fontSize:"13px",fontWeight:500,cursor:"pointer"}},"Reload Interface")))}};'
        '((w=h)=>{n.render(F.createElement(__AgRootEB,null,F.createElement(Fxa.Provider,{value:f},F.createElement(pKd,{...t,featureManagerV2:w,routingConfig:m,updateService:g.updateService,fileSystemProviders:g.fileSystemProviders,MermaidDiagramComponent:jce,WebEffects:g.WebEffects,diagnosticsService:g.diagnosticsService,settingsSyncedState:g.settingsSyncedState}))))})();'
    )
    content = must_replace(content, old_root_render, new_root_render, "root_error_boundary")
    content = apply_application_tab_patch(content)

    assert "Desktop Mode" not in content, "Desktop Mode must not be present in Android main.js"

    with open(file_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)

    print("[web-patch-2.36.0] Successfully patched Android main.js (v2.36.0 + Application Tab + Smart Marketplace V3 + Orchestrator)!")
    return True


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "web/main.js"
    if not patch_main(path):
        sys.exit(1)
