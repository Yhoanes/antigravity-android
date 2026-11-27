#!/usr/bin/env python3
"""
web/patch_main.py - Dynamic patcher for web/main.js (Antigravity v2.35.2 Android UI).

Applies:
1. Android Bridge theme sync hooks (window.Android.getTheme / setTheme / setThemeByUser).
2. Original PC AuxPane v2 redesign (Search tabs/files/plugins/subagents/artifacts/tasks)
   and the header Overview floating popover button ([📄] OverviewToggleIconButton) on Mobile & Desktop.
3. Original PC Customizations (Smart Marketplace V3 | Installed), Automations, and Application Tab inside Settings (⚙️).
4. Voice Mode, Microphone, Permissions Selector badge, Orchestrator Model Selection, /sdcard default path, and touch/menu fixes.
5. Global Root ErrorBoundary (__AgRootEB) above SettingsModal & Router.
"""

import os
import sys


def must_replace(content, old, new, label):
    cnt = content.count(old)
    if cnt != 1:
        raise RuntimeError(f"[web-patch-2.35.2] expected count=1 for {label}, got {cnt}")
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
    'searchMarketplace:async u=>{' + LOADER_INIT_V3 + ';'
    'var _SMR=Rja.file.messages.find(m=>m.name==="SearchMarketplaceResponse")||Rja.file.messages[514],'
    '_q=(u.query??"").trim().toLowerCase(),_gid=u.groupId??"",_pt=u.pageToken??"";'
    'var [_cat,_sr]=await Promise.all([window.__agLoadMarketplaceCatalog().catch(()=>null),_gid==="built-for-android"?Promise.resolve(null):f(v=>v.searchMarketplace(kc(Rja,{query:u.query??"",groupId:_gid,pageToken:_pt}))).catch(()=>null)]);'
    'try{var _allCat=(_cat&&_cat.search&&_cat.search.customizations)||[],_allGrps=(_cat&&_cat.search&&_cat.search.groups)||[],_hasLive=!!(_sr&&_sr.customizations&&_sr.customizations.length>0);'
    'if(_pt&&_hasLive){var _natRaw=_allCat.filter(x=>x._androidNative);if(_natRaw.length>0){var _natMsg=of(_SMR,{customizations:_natRaw,groups:[]},{ignoreUnknownFields:!0}),_nm=new Map((_natMsg.customizations||[]).map(x=>[x.id,x]));_sr.customizations=_sr.customizations.map(x=>_nm.get(x.id)||x)}return _sr}'
    'var _srcItems=_hasLive?_allCat.filter(x=>x._androidNative):_allCat,_srcGrps=_hasLive?_allGrps.filter(x=>x.id==="built-for-android"||x._androidNative):_allGrps;'
    'if(_gid){var _g=_allGrps.find(x=>x.id===_gid);_srcItems=_g?_srcItems.filter(x=>_g.memberIds&&_g.memberIds.includes(x.id)):[]}'
    'if(_q){_srcItems=_srcItems.filter(x=>(x.displayName||"").toLowerCase().includes(_q)||(x.id||"").toLowerCase().includes(_q)||(x.description||"").toLowerCase().includes(_q))}'
    'var _localMsg=of(_SMR,{customizations:_srcItems,groups:_q||_gid?[]:_srcGrps},{ignoreUnknownFields:!0});'
    'if(_hasLive){var _fullNatMsg=of(_SMR,{customizations:_allCat.filter(x=>x._androidNative),groups:[]},{ignoreUnknownFields:!0}),_natById=new Map((_fullNatMsg.customizations||[]).map(x=>[x.id,x])),_seen=new Set((_localMsg.customizations||[]).map(x=>x.id)),_merged=(_localMsg.customizations||[]).slice();'
    'for(var _it of _sr.customizations){var _rep=_natById.get(_it.id)||_it;if(!_seen.has(_rep.id)){_seen.add(_rep.id);_merged.push(_rep)}}'
    'var _gSeen=new Set((_localMsg.groups||[]).map(x=>x.id)),_mGrps=_q||_gid?[]:(_localMsg.groups||[]).slice();'
    'if(!_q&&!_gid&&_sr.groups){for(var _gp of _sr.groups){if(!_gSeen.has(_gp.id)){_gSeen.add(_gp.id);_mGrps.push(_gp)}}}'
    '_localMsg.customizations=_merged;_localMsg.groups=_mGrps;_localMsg.nextPageToken=_sr.nextPageToken||""}'
    'return _localMsg}catch(_e2){return _sr||of(_SMR,{customizations:[],groups:[]},{ignoreUnknownFields:!0})}},'
    'getMarketplaceCustomization:async u=>{' + LOADER_INIT_V3 + ';'
    'var _GCR=Uja.file.messages.find(m=>m.name==="GetMarketplaceCustomizationResponse")||Uja.file.messages[535],'
    '_cat=await window.__agLoadMarketplaceCatalog().catch(()=>null),_localItem=((_cat&&_cat.search&&_cat.search.customizations)||[]).find(x=>x.id===u.id),_localDet=(_cat&&_cat.details&&_cat.details[u.id])||(_localItem?{customization:_localItem}:null);'
    'if((_localItem&&_localItem._androidNative)||(_localDet&&_localDet.customization&&_localDet.customization._androidNative)){return of(_GCR,_localDet,{ignoreUnknownFields:!0})}'
    'try{var _gr=await f(v=>v.getMarketplaceCustomization(kc(Uja,{id:u.id,includePreview:u.includePreview??!1})));if(_gr&&_gr.customization)return _gr}catch(_e){}'
    'return of(_GCR,_localDet||{},{ignoreUnknownFields:!0})},'
    'getMarketplaceBundles:async u=>{var _GBR=Qja.file.messages.find(m=>m.name==="GetMarketplaceBundlesResponse")||Qja.file.messages[512];try{return await f(v=>v.getMarketplaceBundles(kc(Qja,{ids:u.ids??[]})))}catch(_e){return of(_GBR,{bundles:[],customizations:[]},{ignoreUnknownFields:!0})}},'
    'getMarketplaceFeatured:async()=>{' + LOADER_INIT_V3 + ';'
    'var _GFR=Sja.file.messages.find(m=>m.name==="GetMarketplaceFeaturedResponse")||Sja.file.messages[526];'
    'try{var _fr=await f(u=>u.getMarketplaceFeatured(kc(Sja,{})));if(_fr&&_fr.featured&&_fr.featured.length>0)return _fr}catch(_e){}'
    'try{var _cat=await window.__agLoadMarketplaceCatalog();return of(_GFR,{featured:((_cat&&_cat.featured)||[]).filter(x=>x.id!=="android-featured-hero"),customizations:(_cat&&_cat.search&&_cat.search.customizations)||[]},{ignoreUnknownFields:!0})}catch(_e2){return of(_GFR,{featured:[],customizations:[]},{ignoreUnknownFields:!0})}},'
    'getOnboardingPersonas:async()=>f(u=>u.getOnboardingPersonas(kc(Tja,{}))),'
    'installCustomization:async u=>{' + LOADER_INIT_V3 + ';'
    'var _ICR=Vja.file.messages.find(m=>m.name==="InstallCustomizationResponse")||Vja.file.messages[538],'
    '_cat=await window.__agLoadMarketplaceCatalog().catch(()=>null),_c=((_cat&&_cat.search&&_cat.search.customizations)||[]).find(x=>x.id===u.id),_pname=(u.id||"").split("/").pop(),_url=(_c&&_c.source&&_c.source.archive&&_c.source.archive.url)||"",_mp=(_c&&_c.marketplace)||"antigravity-plugins-official",_ver=(_c&&_c.version)||"1.0.0",_isNat=!!(_c&&_c._androidNative)||["agy-video-studio","craft-anything","chrome-devtools-plugin","android-cli-plugin","science"].includes(_pname);'
    'if(_isNat&&window.Android&&window.Android.installMarketplacePlugin){try{var _njr=JSON.parse(window.Android.installMarketplacePlugin(_pname,_url,u.id,_mp,_ver));await new Promise(r=>setTimeout(r,350));return of(_ICR,_njr,{ignoreUnknownFields:!0})}catch(_ne){}}'
    'try{var _ir=await f(v=>v.installCustomization(kc(Vja,{id:u.id,bundleId:u.bundleId})));if(_ir&&_ir.success){try{window.Android&&window.Android.postProcessInstalledPlugin&&window.Android.postProcessInstalledPlugin(_pname)}catch(_pe){}return _ir}}catch(_e){}'
    'try{if(window.Android&&window.Android.installMarketplacePlugin){var _jr=JSON.parse(window.Android.installMarketplacePlugin(_pname,_url,u.id,_mp,_ver));await new Promise(r=>setTimeout(r,350));return of(_ICR,_jr,{ignoreUnknownFields:!0})}}catch(_e2){return of(_ICR,{success:!1,message:String(_e2)},{ignoreUnknownFields:!0})}'
    'return of(_ICR,{success:!1,message:"Marketplace install unavailable"},{ignoreUnknownFields:!0})},'
)


def apply_application_tab_patch(content):
    content = must_replace(
        content,
        'Shortcuts:X$,App:{feature:{visible:!1},applyFirst:[{runtimeGating:{host:[Zi.Electron,Zi.Web.Desktop.RemoteControl,Zi.Web.Mobile.RemoteControl]},feature:{visible:!0}}],important:[W$]}}',
        'Shortcuts:X$,App:{feature:{visible:!0}}}',
        "L3d_App_visible",
    )
    content = must_replace(
        content,
        'O3d={App:{feature:{visible:!1}}}',
        'O3d={App:{feature:{visible:!0}}}',
        "O3d_App_visible",
    )
    content = must_replace(
        content,
        'return E.createElement(YU,{title:"Application",description:`Manage ${b} app settings.`},E.createElement(NV,{screen:"App"})',
        'return E.createElement(YU,{title:"Application",description:`Manage ${b} app settings.`},E.createElement("div",{id:"agy-application-settings-anchor",className:"flex flex-col gap-8 w-full"}),E.createElement(NV,{screen:"App"})',
        "bUb_application_anchor",
    )
    content = must_replace(
        content,
        'var ARb=({title:a="Notifications"})=>{var b=ud().core.notificationService,c=(0,E.useCallback)(async()=>{var d=e,f=e;try{f(await d(b.openPreferences()))}finally{d()}},[b]);',
        'var ARb=({title:a="Notifications"})=>{var b=ud().core.notificationService,c=(0,E.useCallback)(async()=>{try{if(window.Android&&typeof window.Android.openAppSettings==="function"){window.Android.openAppSettings();return}}catch(_e){}var d=e,f=e;try{f(await d(b.openPreferences()))}finally{d()}},[b]);',
        "app_notifications_android_settings",
    )
    return content


def patch_main(file_path):
    if not os.path.isfile(file_path):
        print(f"[web-patch-2.35.2] file not found: {file_path}", file=sys.stderr)
        return False

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    if "Android.getTheme" in content and "__agMpSmartV3" in content and "__AgRootEB" in content and "agy-application-settings-anchor" in content and "E.createElement(Amd,null),q!==void 0" in content:
        print("[web-patch-2.35.2] main.js already patched (v2.35.3 Android UI + Application Tab + Smart Marketplace V3 + RootEB)")
        return True

    # 1a. v0() system theme resolver via window.Android.getTheme()
    content = must_replace(
        content,
        'function v0(){return typeof window!=="undefined"&&window.matchMedia?window.matchMedia("(prefers-color-scheme: dark)").matches:!1}',
        'function v0(){try{if(typeof window!=="undefined"&&window.Android&&typeof window.Android.getTheme==="function"){var _t=window.Android.getTheme();if(_t==="dark")return!0;if(_t==="light")return!1}if(typeof window!=="undefined"&&window.__AG_RESOLVED_THEME__){if(window.__AG_RESOLVED_THEME__==="dark")return!0;if(window.__AG_RESOLVED_THEME__==="light")return!1}}catch(_e){}return typeof window!=="undefined"&&window.matchMedia?window.matchMedia("(prefers-color-scheme: dark)").matches:!1}',
        "v0_system_theme",
    )

    # 1b. HTd(a) hostTheme=system resolver + unconditional startup call
    content = must_replace(
        content,
        'function HTd(a){if(typeof window!=="undefined"){var b=(new URLSearchParams(window.location.search)).get("hostTheme");b!=="dark"&&b!=="light"||Hh.setState(b);',
        'function HTd(a){if(typeof window!=="undefined"){var b=(new URLSearchParams(window.location.search)).get("hostTheme");b==="system"&&(b=typeof window!=="undefined"&&window.Android&&window.Android.getTheme?window.Android.getTheme():(typeof window!=="undefined"?window.__AG_RESOLVED_THEME__:void 0));b!=="dark"&&b!=="light"||Hh.setState(b);',
        "HTd_hostTheme",
    )
    content = must_replace(
        content,
        "d&&HTd(f?.router);",
        "HTd(f?.router);",
        "HTd_startup_call",
    )

    # 1c. Theme sync (window.Android.setTheme, __agOnSystemThemeChanged, setThemeByUser)
    content = must_replace(
        content,
        'A=D=>{m.setState(D);D=w0({hostTheme:b?Hh.getState():void 0,themeMode:D});var F=u.getState();F=D===2?F.light:F.dark;var H=x0(D);r0(H,F,D,b);l.setState(H);z(H)}',
        'A=D=>{m.setState(D);var _origD=D;D=w0({hostTheme:b?Hh.getState():void 0,themeMode:D});var F=u.getState();F=D===2?F.light:F.dark;var H=x0(D);r0(H,F,D,b);l.setState(H);try{var _m=H.uiTheme==="vs-dark"?"dark":"light",_thm=b||_origD!==1?_m:"system";window.__agSelectedTheme=_thm;window.Android&&window.Android.setTheme&&window.Android.setTheme(_thm)}catch(_e){}z(H)}',
        "theme_setTheme",
    )
    content = must_replace(
        content,
        ':window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change",()=>{m.getState()===1&&A(1)});',
        ':(c=()=>{if(c._updating)return;c._updating=!0;try{var _cur=window.Android&&window.Android.getTheme?window.Android.getTheme():window.__AG_RESOLVED_THEME__;if((_cur==="dark"||_cur==="light")&&Hh.getState()!==_cur)Hh.setState(_cur)}catch(_e){}try{m.getState()===1&&A(1)}catch(_e){}try{typeof window!=="undefined"&&window.dispatchEvent(typeof CustomEvent==="function"?new CustomEvent("__ag_theme_change"):new Event("__ag_theme_change"))}catch(_e){}c._updating=!1},c._updating=!1,window.__agOnSystemThemeChanged=c,Hh.onDidChange(()=>{c._updating||c()}),window.matchMedia&&window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change",c));',
        "theme_system_listener",
    )
    content = must_replace(
        content,
        "pushUpdate:D=>{switch(D){case 2:D=2;break;case 3:D=1;break;case 1:D=3;break;default:D=1}xTd(a,D)}",
        'pushUpdate:D=>{var _orig=D;switch(D){case 2:D=2;break;case 3:D=1;break;case 1:D=3;break;default:D=1}xTd(a,D);A(_orig);try{var _um=_orig===2?"light":(_orig===3?"dark":"system");window.Android&&window.Android.setThemeByUser&&window.Android.setThemeByUser(_um)}catch(_e){}}',
        "theme_pushUpdate",
    )

    # 2. AuxPane v2 redesign + Overview [📄] popover button (Amd) on Mobile & Desktop
    content = must_replace(
        content,
        'auxPaneComponent:{feature:{overviewPanelMode:"outputs"}}',
        'auxPaneComponent:{feature:{overviewPanelMode:"full",redesignEnabled:!0}}',
        "auxPane_outputs_to_full",
    )
    content = must_replace(
        content,
        '!b&&E.createElement(E.Fragment,null,\nE.createElement(hnd,{cascadeId:r}),q!==void 0&&E.createElement(ZX,{iconName:"dock_to_bottom",onClick:p,"aria-label":"Toggle Side Panel",dataTestId:"mobile-toggle-aux-sidebar",size:"icon-touch",showAttentionDot:n}))',
        '!b&&E.createElement(E.Fragment,null,\nE.createElement(hnd,{cascadeId:r}),E.createElement(Amd,null),q!==void 0&&E.createElement(ZX,{iconName:"dock_to_bottom",onClick:p,"aria-label":"Toggle Side Panel",dataTestId:"mobile-toggle-aux-sidebar",size:"icon-touch",showAttentionDot:n}))',
        "mobile_header_Amd",
    )
    content = must_replace(
        content,
        'n=u?E.createElement(dxd,{content:aa,sidePanel:Y,statusBar:T}):E.createElement(wxd,',
        'n=u?E.createElement(dxd,{content:F,sidePanel:Y,statusBar:T}):E.createElement(wxd,',
        "mobile_dxd_with_overview_host",
    )
    content = must_replace(
        content,
        'function Ukd(a,b){a=b??a;',
        'function Ukd(a,b){a=a??b;',
        "Ukd_keep_open_when_count_positive",
    )
    content = must_replace(
        content,
        '{id:"modifiedFiles",title:"Modified Files",defaultEnabled:!1}',
        '{id:"modifiedFiles",title:"Modified Files",defaultEnabled:!0}',
        "enable_modifiedFiles_by_default",
    )
    content = must_replace(
        content,
        '{type:"fileView",id:"open-file",title:"Open File",icon:E.createElement(rx,{name:"draft",size:16}),onSelect:({logEvent:a,commandsService:b})=>{a("aux_new_tab_open_file_click");\nb?.executeCommand("openCommandPalette")}}',
        '{type:"fileView",id:"open-file",title:"Open File",icon:E.createElement(rx,{name:"draft",size:16}),onSelect:({logEvent:a,openSidePanelTab:b,panelScope:c})=>{a("aux_new_tab_open_file_click");\nb({type:"fileView",fileView:{}},void 0,{panelScope:c,keepOpen:!0,source:"new_tab_card"})}}',
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
        'x=Eh(uj(b,"enable-sidecars"),C=>k&&(l||C));',
        'x=new Dh(!0);',
        "extensibility_x",
    )
    content = must_replace(
        content,
        'showUISidecars:uj(b,"enable-ui-sidecars"),showDeclaredPermissions:uj(b,"jetski-sidecar-declared-permissions"),',
        'showUISidecars:Gh(!0),showDeclaredPermissions:uj(b,"jetski-sidecar-declared-permissions"),',
        "extensibility_flags",
    )
    content = must_replace(
        content,
        'deletePlugin:async u=>f(async v=>{var w=kc(Dja);w.pluginId=u.pluginId;return v.deletePlugin(w)}),listPluginAuthProviders:async u=>f(v=>v.listPluginAuthProviders(kc(Eja,u??{}))),',
        'deletePlugin:async u=>{try{var _dr=await f(async v=>{var w=kc(Dja);w.pluginId=u.pluginId;return v.deletePlugin(w)});if(_dr&&_dr.success!==!1)return _dr}catch(_e){}try{if(window.Android&&window.Android.uninstallMarketplacePlugin&&window.Android.uninstallMarketplacePlugin(u.pluginId)){await new Promise(r=>setTimeout(r,250));return{success:!0,message:""}}}catch(_e2){}return{success:!1,message:"Failed to delete plugin"}},listPluginAuthProviders:async()=>({providers:[]}),',
        "deletePlugin_and_authProviders_fallback",
    )
    content = must_replace(
        content,
        'searchMarketplace:async u=>f(v=>v.searchMarketplace(kc(Rja,{query:u.query??"",groupId:u.groupId??"",pageToken:u.pageToken??""}))),getMarketplaceCustomization:async u=>f(v=>v.getMarketplaceCustomization(kc(Uja,{id:u.id,includePreview:u.includePreview??!1}))),getMarketplaceBundles:async u=>\nf(v=>v.getMarketplaceBundles(kc(Qja,{ids:u.ids??[]}))),getMarketplaceFeatured:async()=>f(u=>u.getMarketplaceFeatured(kc(Sja,{}))),getOnboardingPersonas:async()=>f(u=>u.getOnboardingPersonas(kc(Tja,{}))),installCustomization:async u=>f(v=>v.installCustomization(kc(Vja,{id:u.id,bundleId:u.bundleId}))),',
        NEW_MP_BLOCK_V3,
        "marketplace_rpcs_v3",
    )
    content = must_replace(
        content,
        'function VW(a,b){return b.find(c=>EYb(c.installedFrom,a))}',
        'function VW(a,b){return b.find(c=>EYb(c.installedFrom,a)||(a?.id&&UW(c)===a.id.split("/").pop()))}',
        "VW_match_by_dir_fallback",
    )
    content = must_replace(
        content,
        '["App",{sections:[{title:"General",settings:["keepComputerAwake","runInBackground"]},{advanced:!0,settings:["autoCheckForUpdates"]}]}]]);',
        '["App",{sections:[{title:"General",settings:["keepComputerAwake","runInBackground"]},{advanced:!0,settings:["autoCheckForUpdates"]}]}],["Automations",{sections:[]}]]);',
        "UOb_automations_screen",
    )
    content = must_replace(
        content,
        '{screen:"Customizations"},{screen:"Browser"},{screen:"Developer"},{screen:"Running"},{screen:"Jetski Chat"}]',
        '{screen:"Customizations"},{screen:"Automations"},{screen:"Browser"},{screen:"Developer"},{screen:"Running"},{screen:"Jetski Chat"}]',
        "bPb_screens",
    )
    content = must_replace(
        content,
        'if(!a)return UOb;var b=new Map(UOb);b.delete("Customizations");return b',
        'return UOb',
        "KU_keep_customizations",
    )
    content = must_replace(
        content,
        'a.get("plugins")&&(l=l.filter(m=>m!=="Customizations"));return l',
        'return l',
        "jXb_keep_customizations_in_settings",
    )
    content = must_replace(
        content,
        'var p0b=()=>{var {authorities:a,selectedAuthority:b,setSelectedAuthority:c,selectedGeminiDir:d,selectedConfigDir:f}=YW(),g=bk().get("projectsCustomizations")?.showHeadCustomizationsInMainScreen??!1,h=(0,E.useMemo)(()=>g?["file:///google/src/files/head/depot/"]:[],[g]);return a.length===0?E.createElement(YU,{title:"Customizations"},E.createElement("div",{className:"flex items-center gap-2 text-muted-foreground"},E.createElement(RE,{size:14,className:"text-muted-foreground"}),"Connecting to language server...")):\nE.createElement(o0b,{key:b,geminiDir:d,configDir:f,workspaceUris:h,tabs:a.map(k=>({id:k.authority,label:k.displayName})),selectedAuthority:b,onSelectAuthority:c})};',
        'var p0b=()=>E.createElement(Pec,null,E.createElement(dM,null,E.createElement(tkd,{embedded:!0})));',
        "p0b_replace_with_marketplace_tkd",
    )
    content = must_replace(
        content,
        'case "Customizations":return E.createElement(p0b,null);',
        'case "Customizations":return E.createElement(p0b,null);case "Automations":return E.createElement(TAd,{automationPage:!0});',
        "mobile_automations_screen",
    )
    content = must_replace(
        content,
        'E.createElement("div",\n{style:{display:b==="Customizations"?"block":"none"}},E.createElement(p0b,null))',
        'E.createElement("div",\n{style:{display:b==="Customizations"?"block":"none"}},E.createElement(p0b,null)),E.createElement("div",{style:{display:b==="Automations"?"block":"none"}},E.createElement(TAd,{automationPage:!0}))',
        "desktop_settings_automations_screen",
    )
    content = must_replace(
        content,
        'c({to:"/",search:l=>{l={...l};delete l.cascadeId;delete l.focused;',
        'c({to:"/",search:l=>{l={...l};delete l.cascadeId;delete l.focused;delete l.settingsOpen;delete l.settingsScreen;delete l.settingsProjectId;delete l.settingsSection;',
        "vS_close_settings",
    )

    # Dual-mode nkd / tkd inside Settings modal and on /customizations route
    old_nkd = (
        'function nkd({tab:a,onSelect:b}){return E.createElement("nav",{"aria-label":"Customizations views",className:"agy-pill inline-flex items-center h-8 rounded-lg border border-border bg-secondary/20 p-0.5 gap-0.5 select-none","data-testid":"plugins-tabs"},mkd.map(({tab:c,to:d,label:f})=>{var g=c===a;return E.createElement(OR,{key:c,to:d,activeOptions:{exact:!0},"aria-current":g?"page":void 0,onClick:()=>{g||b(c)},className:kj("agy-pill inline-flex items-center justify-center h-full px-2 rounded-md shrink-0 whitespace-nowrap no-underline transition-colors cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-primary",\n'
        'g?"bg-secondary text-foreground":"text-secondary-foreground hover:text-foreground"),"data-testid":`plugins-tab-${c}`},f)}))}'
    )
    new_nkd = (
        'function nkd({tab:a,onSelect:b,inRoute:inR=!0}){return E.createElement("nav",{"aria-label":"Customizations views",className:"agy-pill inline-flex items-center h-8 rounded-lg border border-border bg-secondary/20 p-0.5 gap-0.5 select-none","data-testid":"plugins-tabs"},mkd.map(({tab:c,to:d,label:f})=>{var g=c===a,cls=kj("agy-pill inline-flex items-center justify-center h-full px-2 rounded-md shrink-0 whitespace-nowrap no-underline transition-colors cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-primary",g?"bg-secondary text-foreground":"text-secondary-foreground hover:text-foreground");return inR?E.createElement(OR,{key:c,to:d,activeOptions:{exact:!0},"aria-current":g?"page":void 0,onClick:()=>{g||b(c)},className:cls,"data-testid":`plugins-tab-${c}`},f):E.createElement("button",{key:c,type:"button","aria-current":g?"page":void 0,onClick:()=>{g||b(c)},className:kj(cls,"border-none bg-transparent"),"data-testid":`plugins-tab-${c}`},f)}))}'
    )
    content = must_replace(content, old_nkd, new_nkd, "nkd_dual_mode")

    old_tkd = (
        'function tkd({MarkdownRenderer:a}={}){var {q:b}=SR({strict:!1}),c=GR({select:C=>C.location.pathname}),d=bk().get("customizations"),f=d?.showMarketplaceTab??!0,g=!f||c.startsWith("/customizations/installed")?"installed":"browse";c=d?.pluginSources??"local";var h=x_(),k=c==="remote",[l,m]=(0,E.useState)(()=>b??h.searchQuery),[n,p]=(0,E.useState)(b);n!==b&&(p(b),b!==void 0&&m(b));(0,E.useEffect)(()=>{h.searchQuery=l},[h,l]);d=(d?.rankedMarketplace??!1)&&!k;var [q,r]=(0,E.useState)(()=>h.marketplaceCategory);\n'
        '(0,E.useEffect)(()=>{h.marketplaceCategory=q},[h,q]);c=d?q?.id??"":"";var u=(0,E.useRef)(null),v=(0,E.useCallback)(C=>{r(C);u.current&&(u.current.scrollTop=0)},[]),w=d&&g==="browse",x=E.createElement(lkd,{searchQuery:l,onSearchQueryChange:m,placeholder:"Search customizations...",inputTestId:"customizations-search",chips:w&&q?[{id:"category",label:q.label,removeLabel:"Clear category",onRemove:()=>{v(null)},testId:"marketplace-category-chip"}]:[]}),{logTabToggle:y,logSearch:z}=W4(),A=Oyc(l,800),B=(0,E.useRef)(l);\n'
        '(0,E.useEffect)(()=>{k||A===B.current||(B.current=A)&&z(g,A)},[A,g,k,z]);return E.createElement(skd,{searchQuery:k?"":l,category:c,onSelectCategory:d?v:void 0,rankedMarketplace:d},E.createElement("div",{className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},E.createElement("div",{ref:u,className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},E.createElement("div",{className:"sticky top-0 z-20 bg-background"},E.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 pt-8 pb-4 flex flex-col gap-4"},\n'
        'E.createElement("div",{className:"flex flex-wrap items-center justify-between gap-2 sm:gap-4"},E.createElement("h1",{className:"text-xl font-medium text-foreground select-none sm:px-3 m-0"},"Customizations"),E.createElement("div",{className:"flex items-center gap-4"},f&&E.createElement(nkd,{tab:g,onSelect:y}))),d?E.createElement("div",{className:"flex items-center gap-2"},E.createElement("div",{className:"min-w-0 flex-1"},x),w&&E.createElement(okd,{category:q,onCategoryChange:v})):x)),E.createElement("div",\n'
        '{className:"max-w-4xl w-full mx-auto px-4 sm:px-8 py-6"},E.createElement(yid,{value:l},k?E.createElement(qkd,{tab:g,MarkdownRenderer:a}):E.createElement(KR,null))))))}'
    )
    new_tkd = (
        'function tkd({MarkdownRenderer:a,embedded:emb=!1}={}){var {q:b}=SR({strict:!1}),c=GR({select:C=>C.location.pathname}),pMid=E.useContext(IR),inR=!emb&&pMid!==void 0&&c.startsWith("/customizations"),mdR=a||h5,[locTab,setLocTab]=(0,E.useState)("browse"),[det,setDet]=(0,E.useState)(null),d=bk().get("customizations"),f=d?.showMarketplaceTab??!0,g=!f?"installed":inR?(c.startsWith("/customizations/installed")?"installed":"browse"):locTab;c=d?.pluginSources??"local";var h=x_(),k=c==="remote",[l,m]=(0,E.useState)(()=>b??h.searchQuery),[n,p]=(0,E.useState)(b);n!==b&&(p(b),b!==void 0&&m(b));(0,E.useEffect)(()=>{h.searchQuery=l},[h,l]);'
        '(0,E.useEffect)(()=>{if(!inR){window.__agCustSelect=setDet;window.__agCustTab=setLocTab;return()=>{if(window.__agCustSelect===setDet)window.__agCustSelect=null;if(window.__agCustTab===setLocTab)window.__agCustTab=null}}},[inR]);'
        'd=(d?.rankedMarketplace??!1)&&!k;var [q,r]=(0,E.useState)(()=>h.marketplaceCategory);(0,E.useEffect)(()=>{h.marketplaceCategory=q},[h,q]);c=d?q?.id??"":"";var u=(0,E.useRef)(null),v=(0,E.useCallback)(C=>{r(C);u.current&&(u.current.scrollTop=0)},[]),w=d&&g==="browse",x=E.createElement(lkd,{searchQuery:l,onSearchQueryChange:m,placeholder:"Search customizations...",inputTestId:"customizations-search",chips:w&&q?[{id:"category",label:q.label,removeLabel:"Clear category",onRemove:()=>{v(null)},testId:"marketplace-category-chip"}]:[]}),{logTabToggle:y,logSearch:z}=W4(),A=Oyc(l,800),B=(0,E.useRef)(l);'
        '(0,E.useEffect)(()=>{k||A===B.current||(B.current=A)&&z(g,A)},[A,g,k,z]);var onTab=tb=>{inR||setLocTab(tb);setDet(null);y(tb)};'
        'if(!inR&&det)return E.createElement("div",{"data-agy-embedded-cust":"1",className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},E.createElement("div",{className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},E.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 py-4"},E.createElement("button",{type:"button",onClick:()=>setDet(null),className:"mb-4 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground bg-transparent border-none cursor-pointer p-0"},E.createElement(rx,{name:"arrow_back",size:16}),"Customizations"),det.bundleId?E.createElement(rid,{bundleId:det.bundleId}):E.createElement(d1c,{installDir:det.installDir,catalogId:det.catalogId,onUninstalled:()=>setDet(null),onDisconnected:()=>setDet(null),MarkdownRenderer:mdR}))));'
        'return E.createElement(skd,{searchQuery:k?"":g==="browse"?l:"",category:c,onSelectCategory:d?v:void 0,rankedMarketplace:d},E.createElement("div",{"data-agy-embedded-cust":inR?void 0:"1",className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},E.createElement("div",{ref:u,className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},E.createElement("div",{className:"sticky top-0 z-20 bg-background"},E.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 pt-6 pb-4 flex flex-col gap-4"},E.createElement("div",{className:"flex flex-wrap items-center justify-between gap-2 sm:gap-4"},E.createElement("h1",{className:"text-xl font-medium text-foreground select-none sm:px-3 m-0"},"Customizations"),E.createElement("div",{className:"flex items-center gap-4"},f&&E.createElement(nkd,{tab:g,onSelect:onTab,inRoute:inR}))),d?E.createElement("div",{className:"flex items-center gap-2"},E.createElement("div",{className:"min-w-0 flex-1"},x),w&&E.createElement(okd,{category:q,onCategoryChange:v})):x)),E.createElement("div",{className:"max-w-4xl w-full mx-auto px-4 sm:px-8 py-6"},E.createElement(yid,{value:l},k?E.createElement(qkd,{tab:g,MarkdownRenderer:mdR}):inR?E.createElement(KR,null):(g==="browse"?E.createElement(ikd,{MarkdownRenderer:mdR}):E.createElement(Rjd,{MarkdownRenderer:mdR})))))))}'
    )
    content = must_replace(content, old_tkd, new_tkd, "tkd_dual_mode")

    content = must_replace(
        content,
        'g=ul(u=>u.newConvoResourceSelector.selectedEnvironmentByProject),h=c.get("currentWorkspace")?.workspaceUri??void 0,k=a||b?.project?.id;a=(0,E.useMemo)(()=>k?d.getProjectStateProvider(k):void 0,[k,d]);a=xl(a);var l=k?g[k]:void 0',
        'g=ul(u=>u.newConvoResourceSelector?.selectedEnvironmentByProject),h=c.get("currentWorkspace")?.workspaceUri??void 0,k=a||b?.project?.id;a=(0,E.useMemo)(()=>k?d.getProjectStateProvider(k):void 0,[k,d]);a=xl(a);var l=k&&g?g[k]:void 0',
        "safe_redux_selectedEnvironmentByProject",
    )

    content = must_replace(
        content,
        'return GR({select:c=>{c=c.matches.find(d=>a.from?a.from===d.routeId:d.id===b);JR(!((a.shouldThrow??!0)&&!c),`Could not find ${a.from?`an active match from "${a.from}"`:"a nearest match!"}`);if(c!==void 0)return a.select?a.select(c):c},structuralSharing:a.structuralSharing})}',
        'return GR({select:c=>{c=c.matches.find(d=>a.from?a.from===d.routeId:d.id===b)||c.matches[c.matches.length-1]||{params:{},_strictParams:{},search:{},_strictSearch:{}};if(c!==void 0)return a.select?a.select(c):c},structuralSharing:a.structuralSharing})}',
        "route_match_fallback_outside_outlet",
    )

    content = must_replace(
        content,
        'E.createElement(OR,{to:d,params:f,search:g,onClick:m,"aria-label":p,"aria-describedby":x,className:"rounded-sm after:absolute after:inset-0 after:rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary text-left"}',
        'E.createElement(OR,{to:d,params:f,search:g,onClick:ev=>{m?.(ev);if(typeof window!=="undefined"&&window.__agCustSelect&&ev?.currentTarget?.closest?.(\'[data-agy-embedded-cust]\')){ev.preventDefault();window.__agCustSelect({installDir:d==="/customizations/plugins/$pluginId"?f?.pluginId:void 0,catalogId:g?.id||(f?lL(f):void 0)})}},"aria-label":p,"aria-describedby":x,className:"rounded-sm after:absolute after:inset-0 after:rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary text-left"}',
        "z5_card_detail_in_settings",
    )

    content = must_replace(
        content,
        'E.createElement(OR,{to:"/customizations",search:c?{q:b}:void 0,className:d},f)',
        'E.createElement(OR,{to:"/customizations",search:c?{q:b}:void 0,onClick:ev=>{if(typeof window!=="undefined"&&window.__agCustTab&&ev?.currentTarget?.closest?.(\'[data-agy-embedded-cust]\')){ev.preventDefault();window.__agCustTab("browse")}},className:d},f)',
        "browse_in_settings",
    )

    content = must_replace(
        content,
        'var U_=()=>{var {getActiveEditor:a}=(0,E.useContext)(YS),{logEvent:b}=rL(),c=vS();return(0,E.useCallback)((d,f)=>{b("AGENT_MANAGER_HOME");c({project:d,clearProject:f?.clearProject});queueMicrotask(()=>{var g=a();g&&document.hasFocus()&&wz(g)})},[a,b,c])};',
        'var U_=()=>{var {getActiveEditor:a}=(0,E.useContext)(YS),{logEvent:b}=rL(),c=vS(),hideS=El()?.useCloseSettings?.();return(0,E.useCallback)((d,f)=>{hideS?.();b("AGENT_MANAGER_HOME");c({project:d,clearProject:f?.clearProject});queueMicrotask(()=>{var g=a();g&&document.hasFocus()&&wz(g)})},[a,b,c,hideS])};',
        "U__close_settings_on_prompt",
    )

    content = must_replace(
        content,
        'fCb=()=>{var a=PR(),b=WBb();return(0,E.useCallback)(c=>{var d=b();a({to:"/sidecars/$sidecarId",params:{sidecarId:c},...(d?{search:d}:{})})},[a,b])}',
        'fCb=()=>{var a=PR(),b=WBb(),hideS=El()?.useCloseSettings?.();return(0,E.useCallback)(c=>{hideS?.();var d=b();a({to:"/sidecars/$sidecarId",params:{sidecarId:c},...(d?{search:d}:{})})},[a,b,hideS])}',
        "fCb_close_settings",
    )

    content = must_replace(
        content,
        'hCb=()=>{var a=PR(),b=WBb();return(0,E.useCallback)(c=>{var d=b();a({to:"/sidecars/triggers/$triggerId",params:{triggerId:c},...(d?{search:d}:{})})},[a,b])}',
        'hCb=()=>{var a=PR(),b=WBb(),hideS=El()?.useCloseSettings?.();return(0,E.useCallback)(c=>{hideS?.();var d=b();a({to:"/sidecars/triggers/$triggerId",params:{triggerId:c},...(d?{search:d}:{})})},[a,b,hideS])}',
        "hCb_close_settings",
    )

    content = must_replace(
        content,
        'k=b?mRd(b):"/",l=f(await d(g({initialPath:k,',
        'k=b?mRd(b):"/sdcard",l=f(await d(g({initialPath:k,',
        "default_sdcard_path",
    )

    # 4. Enable Voice Mode, Microphone, Permissions Selector, Orchestrator Model Selection, and touch/menu fixes
    content = must_replace(
        content,
        'inputComponent:{feature:{hideAgentSelectorIfEmpty:!1,hideInSubagentConversation:!1,showPermissionsSelector:!1,disableInlineCompletion:!0,showInternalModesDropdown:!1,enableMicrophone:f,showModelUsage:!0,enableFileSystemCategoriesInMentions:!1,enableGoogleWorkspaceMentions:!1,\nenableVoiceRelay:!1,enablePasteTextAsSnippet:!1,enableHelpCommand:!1',
        'inputComponent:{feature:{hideAgentSelectorIfEmpty:!1,hideInSubagentConversation:!1,showPermissionsSelector:!0,disableInlineCompletion:!0,showInternalModesDropdown:!1,enableMicrophone:!0,showModelUsage:!0,enableFileSystemCategoriesInMentions:!1,enableGoogleWorkspaceMentions:!1,\nenableVoiceRelay:!0,enablePasteTextAsSnippet:!0,enableHelpCommand:!1',
        "inputComponent_full",
    )
    content = must_replace(
        content,
        '{runtimeGating:{host:[Zi.Web.Desktop,Zi.Electron],customFlag:"enable-permissions-selector"},\nfeature:{showPermissionsSelector:!0}}',
        '{runtimeGating:{customFlag:"enable-permissions-selector"},\nfeature:{showPermissionsSelector:!0}}',
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
        'Za=[...(R9()?["enable-developer-mode"]:[])',
        'Za=["enable-voice-relay","enable-voice-remote","enable-permissions-selector","enable-paste-text-as-snippet","jetski-autonomous-mode","enable-orchestrator-model-selection",...(R9()?["enable-developer-mode"]:[])',
        "Za_customFlags",
    )
    content = must_replace(
        content,
        'let TA={isAvailable:!1,voiceState:"off",voiceConvId:null,muted:!1,liveLines:OA,speed:1};',
        'let TA={isAvailable:!0,voiceState:"off",voiceConvId:null,muted:!1,liveLines:OA,speed:1};',
        "TA_voice_isAvailable",
    )
    content = must_replace(
        content,
        'some(k=>k.kind==="audioinput");b(h)}else b(!1)',
        'some(k=>k.kind==="audioinput");b(!0)}else b(!0)',
        "hasMicrophone_always_true",
    )
    content = must_replace(
        content,
        'enabled:uj(a,"enable-audio-transcription")}',
        'enabled:Gh(!0)}',
        "audioTranscription_enabled",
    )
    content = must_replace(
        content,
        'var I=!n&&c;v=(x||v)&&w;w=a&&!c&&!z?.isOn;var J=b??a;a=q===\n"idle"&&!n&&!D;f=f||l||m||p;l=!!z&&!z.isOn&&!f&&!c&&a;',
        'var I=!n&&c;v=!!w;w=a&&!c&&!z?.isOn;var J=b??a;a=q===\n"idle"&&!D;f=f||l||m||p;l=!!z&&!z.isOn&&!f&&!c&&a;',
        "micAndVoice_idle",
    )
    content = must_replace(
        content,
        "uc=$ed({cascadeId:O,planModel:A?.value,enabled:!ta&&li&&(ua??bQ).voice&&(!bb||!!O)})",
        "uc=$ed({cascadeId:O,planModel:A?.value||1018,enabled:!ta&&li&&(ua??bQ).voice&&(!bb||!!O)})",
        "$ed_planModel_fallback",
    )
    content = must_replace(
        content,
        '"shrink-0 whitespace-nowrap @max-[400px]/input-footer:w-7 @max-[400px]/input-footer:justify-center @max-[400px]/input-footer:!p-1.5",J&&"!cursor-not-allowed opacity-50 hover:!bg-transparent")},E.createElement(jcd,\n{preset:c,className:"@max-[400px]/input-footer:!h-4 @max-[400px]/input-footer:!w-4"}),E.createElement("span",{className:"@max-[400px]/input-footer:hidden"},z),!J&&E.createElement(rx,{name:"keyboard_arrow_down",size:14,className:"shrink-0 @max-[400px]/input-footer:hidden"})',
        '"shrink-0 whitespace-nowrap",J&&"!cursor-not-allowed opacity-50 hover:!bg-transparent")},E.createElement(jcd,{preset:c,className:""}),E.createElement("span",{className:""},z),!J&&E.createElement(rx,{name:"keyboard_arrow_down",size:14,className:"shrink-0"})',
        "mobilePermissionsBadge",
    )

    # 5. Touch & Marketplace refresh fixes
    content = must_replace(
        content,
        "pf=qf=>{var Ij=gd.current?.getRootElement();Ij&&document.activeElement===Ij&&!Ij.contains(qf.target)&&\nqf.preventDefault()}",
        'pf=qf=>{var Ij=gd.current?.getRootElement();Ij&&document.activeElement===Ij&&!Ij.contains(qf.target)&&!(qf.target instanceof Element&&qf.target.closest(\'button,[role="button"],[aria-haspopup],[data-testid="project-selector-trigger"],[data-testid="model-selector-trigger"]\'))&&qf.preventDefault()}',
        "pointerdown_allow_menu_triggers",
    )
    content = must_replace(
        content,
        'return{onPointerDown(x){p.current=x.pointerType},\nonMouseDown(x){var y=p.current,z=x.nativeEvent,A=m.select("open");if(!(x.button!==0||d==="click"||qG(y,!0)&&g)){var B=w(A,x.currentTarget,D=>D==="click"||D==="mousedown");A=iG(z);if(mG(A))v(B,z,A,y);else{var C=x.currentTarget;q.request(()=>{v(B,z,C,y)})}}},onClick(x){if(d!=="mousedown-only"){var y=p.current;if(d==="mousedown"&&y)p.current=void 0;else if(!qG(y,!0)||!g){var z=m.select("open");z=w(z,x.currentTarget,A=>A==="click"||A==="mousedown"||A==="keydown"||A==="keyup");v(z,x.nativeEvent,x.currentTarget,\ny)}}},onKeyDown(){p.current=void 0}}',
        'return{onPointerDown(x){p.current=x.pointerType;p.md=!1},\nonMouseDown(x){var y=p.current,z=x.nativeEvent,A=m.select("open");if(!(x.button!==0||d==="click"||qG(y,!0)&&g)){p.md=!0;var B=w(A,x.currentTarget,D=>D==="click"||D==="mousedown");A=iG(z);if(mG(A))v(B,z,A,y);else{var C=x.currentTarget;q.request(()=>{v(B,z,C,y)})}}},onClick(x){if(d!=="mousedown-only"){var y=p.current;if(d==="mousedown"&&y&&p.md)p.current=void 0,p.md=!1;else if(p.current=void 0,p.md=!1,!qG(y,!0)||!g){var z=m.select("open");z=w(z,x.currentTarget,A=>A==="click"||A==="mousedown"||A==="keydown"||A==="keyup");v(z,x.nativeEvent,x.currentTarget,\ny)}}},onKeyDown(){p.current=void 0;p.md=!1}}',
        "BaseUI_click_fallback_when_mousedown_prevented",
    )
    content = must_replace(
        content,
        "async function u_(a){var b=e,c=e;try{a.isStreaming()?c(await b(a.refreshSkills())):c(await b(a.refresh()))}finally{b()}}",
        "async function u_(a){var b=e,c=e;try{c(await b(a.refresh()))}finally{b()}}",
        "u__always_refresh_plugins_and_skills",
    )

    # 6. Global Root ErrorBoundary (__AgRootEB) above SettingsModal & Router to prevent #root unmount gray screens
    old_root_render = (
        '((u=h)=>{n.render(E.createElement(qsa.Provider,{value:f},E.createElement(YBd,{...q,featureManagerV2:u,routingConfig:m,updateService:g.updateService,\n'
        'fileSystemProviders:g.fileSystemProviders,MermaidDiagramComponent:B4d,WebEffects:g.WebEffects,diagnosticsService:g.diagnosticsService,settingsSyncedState:g.settingsSyncedState})))})();'
    )
    new_root_render = (
        'var __AgRootEB=class extends E.Component{constructor(pr){super(pr);this.state={err:null,retries:0}}static getDerivedStateFromError(er){return{err:er||Error("UI render error")}}componentDidCatch(er,inf){try{console.error("[AGY RootErrorBoundary]",er,inf&&inf.componentStack)}catch(_e){}if(this.state.retries<1){var _s=this;setTimeout(function(){try{if(window.history&&window.location&&window.location.search.indexOf("settingsOpen")!==-1){var _u=new URL(window.location.href);_u.searchParams.delete("settingsOpen");_u.searchParams.delete("settingsScreen");window.history.replaceState(window.history.state,"",_u.pathname+_u.search+_u.hash)}}catch(_e2){}_s.setState(function(st){return{err:null,retries:st.retries+1}})},60)}}render(){if(!this.state.err)return this.props.children;if(this.state.retries<1)return null;var _msg=String(this.state.err&&(this.state.err.message||this.state.err)||"Unexpected UI error");return E.createElement("div",{"data-testid":"agy-root-error-fallback",style:{display:"flex",flexDirection:"column",alignItems:"center",justifyContent:"center",height:"100vh",width:"100vw",padding:"24px",boxSizing:"border-box",background:"var(--background,#101010)",color:"var(--foreground,#f4f4f5)",fontFamily:"system-ui,-apple-system,sans-serif",textAlign:"center",gap:"12px"}},E.createElement("div",{style:{fontSize:"16px",fontWeight:600}},"Interface recovered from a view error"),E.createElement("div",{style:{fontSize:"12px",opacity:.72,maxWidth:"440px",wordBreak:"break-word"}},_msg),E.createElement("div",{style:{display:"flex",gap:"10px",marginTop:"6px"}},E.createElement("button",{type:"button",onClick:()=>this.setState({err:null}),style:{padding:"8px 14px",borderRadius:"8px",border:"1px solid rgba(128,128,128,0.35)",background:"transparent",color:"inherit",fontSize:"13px",cursor:"pointer"}},"Retry View"),E.createElement("button",{type:"button",onClick:()=>{try{window.location.reload()}catch(_e){}},style:{padding:"8px 14px",borderRadius:"8px",border:"none",background:"#3b82f6",color:"#fff",fontSize:"13px",fontWeight:500,cursor:"pointer"}},"Reload Interface")))}};'
        '((u=h)=>{n.render(E.createElement(__AgRootEB,null,E.createElement(qsa.Provider,{value:f},E.createElement(YBd,{...q,featureManagerV2:u,routingConfig:m,updateService:g.updateService,\n'
        'fileSystemProviders:g.fileSystemProviders,MermaidDiagramComponent:B4d,WebEffects:g.WebEffects,diagnosticsService:g.diagnosticsService,settingsSyncedState:g.settingsSyncedState}))))})();'
    )
    content = must_replace(content, old_root_render, new_root_render, "root_error_boundary")
    content = apply_application_tab_patch(content)

    assert "Desktop Mode" not in content, "Desktop Mode must not be present in Android main.js"

    with open(file_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)

    print("[web-patch-2.35.2] Successfully patched Android main.js (v2.35.2 + Application Tab + Smart Marketplace V3 + Orchestrator)!")
    return True


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "web/main.js"
    if not patch_main(path):
        sys.exit(1)
