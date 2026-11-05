import{a as ot}from"./chunk-MCIMMS2E.js";import{a as lt}from"./chunk-E4WJ6U24.js";import{a as at}from"./chunk-TLNPXETV.js";import{a as Le,b as Re}from"./chunk-AXVRDHPJ.js";import{a as Me,b as Ee,c as Se,f as Te,g as Ie}from"./chunk-EXGGNIVR.js";import{a as je,b as He}from"./chunk-ZY2W7FLM.js";import{a as Ke,b as et,c as tt}from"./chunk-DEXWALJ5.js";import{a as Ze,c as Je}from"./chunk-RHPFFWFU.js";import{a as it,b as nt}from"./chunk-ED4ARVCW.js";import{a as Ve}from"./chunk-KP7SXFUC.js";import{b as Fe,d as Ne,h as Ae}from"./chunk-KVL42LHG.js";import{a as We,b as Ye}from"./chunk-UCLTEWQF.js";import"./chunk-2VTFGMTI.js";import"./chunk-6WJKOI4Y.js";import"./chunk-2SRW3P5S.js";import"./chunk-D6K2TWTI.js";import{a as me,b as pe,c as ge,d as he,f as ue,g as _e,i as fe,k as be,l as ve,m as xe,n as Ce,p as we,r as ye}from"./chunk-DZUWL2SU.js";import{a as Be,b as Ge,c as Ue}from"./chunk-OTOJIHQB.js";import{A as Oe,N as ze,P as qe,R as $e,T as Xe,U as Qe,b as ke,k as Pe,m as De}from"./chunk-CTZWC4FF.js";import{a as de,d as se}from"./chunk-MI23EID5.js";import{i as ce}from"./chunk-R44X3CHN.js";import{$ as f,Ab as x,Bb as C,Bc as I,Cb as g,Db as n,Dc as ae,Eb as i,Ec as oe,Fb as _,Kc as le,Mb as E,Nb as Q,Oc as M,Pc as re,Qb as u,Sa as P,Sb as d,Tb as W,Ua as o,Ub as Y,V as q,Wb as Z,X as $,Xb as J,Yb as K,Z as B,ac as z,cc as S,dc as ee,ea as b,ec as l,fa as v,fc as s,ga as G,gc as k,hb as D,hc as te,ib as X,ma as N,p as R,pc as ie,qc as ne,ra as y,sc as A,tc as V,ua as U,va as j,wb as O,xb as m,ya as H,yb as p}from"./chunk-TMT6QMOP.js";import"./chunk-7CGTOI24.js";var st=["switch"],mt=["*"];function pt(t,a){t&1&&(n(0,"span",11),G(),n(1,"svg",13),_(2,"path",14),i(),n(3,"svg",15),_(4,"path",16),i()())}var gt=new B("mat-slide-toggle-default-options",{providedIn:"root",factory:()=>({disableToggleValue:!1,hideIcon:!1,disabledInteractive:!1})}),F=class{source;checked;constructor(a,e){this.source=a,this.checked=e}},L=(()=>{class t{_elementRef=f(H);_focusMonitor=f(Pe);_changeDetectorRef=f(le);defaults=f(gt);_onChange=e=>{};_onTouched=()=>{};_validatorOnChange=()=>{};_uniqueId;_checked=!1;_createChangeEvent(e){return new F(this,e)}_labelId;get buttonId(){return`${this.id||this._uniqueId}-button`}_switchElement;focus(){this._switchElement.nativeElement.focus()}_noopAnimations=ze();_focused=!1;name=null;id;labelPosition="after";ariaLabel=null;ariaLabelledby=null;ariaDescribedby;required=!1;color;disabled=!1;disableRipple=!1;tabIndex=0;get checked(){return this._checked}set checked(e){this._checked=e,this._changeDetectorRef.markForCheck()}hideIcon;disabledInteractive;change=new N;toggleChange=new N;get inputId(){return`${this.id||this._uniqueId}-input`}constructor(){f(De).load($e);let e=f(new oe("tabindex"),{optional:!0}),c=this.defaults;this.tabIndex=e==null?0:parseInt(e)||0,this.color=c.color||"accent",this.id=this._uniqueId=f(Oe).getId("mat-mdc-slide-toggle-"),this.hideIcon=c.hideIcon??!1,this.disabledInteractive=c.disabledInteractive??!1,this._labelId=this._uniqueId+"-label"}ngAfterContentInit(){this._focusMonitor.monitor(this._elementRef,!0).subscribe(e=>{e==="keyboard"||e==="program"?(this._focused=!0,this._changeDetectorRef.markForCheck()):e||Promise.resolve().then(()=>{this._focused=!1,this._onTouched(),this._changeDetectorRef.markForCheck()})})}ngOnChanges(e){e.required&&this._validatorOnChange()}ngOnDestroy(){this._focusMonitor.stopMonitoring(this._elementRef)}writeValue(e){this.checked=!!e}registerOnChange(e){this._onChange=e}registerOnTouched(e){this._onTouched=e}validate(e){return this.required&&e.value!==!0?{required:!0}:null}registerOnValidatorChange(e){this._validatorOnChange=e}setDisabledState(e){this.disabled=e,this._changeDetectorRef.markForCheck()}toggle(){this.checked=!this.checked,this._onChange(this.checked)}_emitChangeEvent(){this._onChange(this.checked),this.change.emit(this._createChangeEvent(this.checked))}_handleClick(){this.disabled||(this.toggleChange.emit(),this.defaults.disableToggleValue||(this.checked=!this.checked,this._onChange(this.checked),this.change.emit(new F(this,this.checked))))}_getAriaLabelledBy(){return this.ariaLabelledby?this.ariaLabelledby:this.ariaLabel?null:this._labelId}static \u0275fac=function(c){return new(c||t)};static \u0275cmp=D({type:t,selectors:[["mat-slide-toggle"]],viewQuery:function(c,r){if(c&1&&Z(st,5),c&2){let h;J(h=K())&&(r._switchElement=h.first)}},hostAttrs:[1,"mat-mdc-slide-toggle"],hostVars:13,hostBindings:function(c,r){c&2&&(Q("id",r.id),O("tabindex",null)("aria-label",null)("name",null)("aria-labelledby",null),ee(r.color?"mat-"+r.color:""),S("mat-mdc-slide-toggle-focused",r._focused)("mat-mdc-slide-toggle-checked",r.checked)("_mat-animation-noopable",r._noopAnimations))},inputs:{name:"name",id:"id",labelPosition:"labelPosition",ariaLabel:[0,"aria-label","ariaLabel"],ariaLabelledby:[0,"aria-labelledby","ariaLabelledby"],ariaDescribedby:[0,"aria-describedby","ariaDescribedby"],required:[2,"required","required",M],color:"color",disabled:[2,"disabled","disabled",M],disableRipple:[2,"disableRipple","disableRipple",M],tabIndex:[2,"tabIndex","tabIndex",e=>e==null?0:re(e)],checked:[2,"checked","checked",M],hideIcon:[2,"hideIcon","hideIcon",M],disabledInteractive:[2,"disabledInteractive","disabledInteractive",M]},outputs:{change:"change",toggleChange:"toggleChange"},exportAs:["matSlideToggle"],features:[ie([{provide:me,useExisting:q(()=>t),multi:!0},{provide:ge,useExisting:t,multi:!0}]),j],ngContentSelectors:mt,decls:14,vars:27,consts:[["switch",""],["mat-internal-form-field","",3,"labelPosition"],["role","switch","type","button",1,"mdc-switch",3,"click","tabIndex","disabled"],[1,"mat-mdc-slide-toggle-touch-target"],[1,"mdc-switch__track"],[1,"mdc-switch__handle-track"],[1,"mdc-switch__handle"],[1,"mdc-switch__shadow"],[1,"mdc-elevation-overlay"],[1,"mdc-switch__ripple"],["mat-ripple","",1,"mat-mdc-slide-toggle-ripple","mat-focus-indicator",3,"matRippleTrigger","matRippleDisabled","matRippleCentered"],[1,"mdc-switch__icons"],[1,"mdc-label",3,"click","for"],["viewBox","0 0 24 24","aria-hidden","true",1,"mdc-switch__icon","mdc-switch__icon--on"],["d","M19.69,5.23L8.96,15.96l-4.23-4.23L2.96,13.5l6,6L21.46,7L19.69,5.23z"],["viewBox","0 0 24 24","aria-hidden","true",1,"mdc-switch__icon","mdc-switch__icon--off"],["d","M20 13H4v-2h16v2z"]],template:function(c,r){if(c&1&&(W(),n(0,"div",1)(1,"button",2,0),u("click",function(){return r._handleClick()}),_(3,"div",3)(4,"span",4),n(5,"span",5)(6,"span",6)(7,"span",7),_(8,"span",8),i(),n(9,"span",9),_(10,"span",10),i(),m(11,pt,5,0,"span",11),i()()(),n(12,"label",12),u("click",function(T){return T.stopPropagation()}),Y(13),i()()),c&2){let h=z(2);g("labelPosition",r.labelPosition),o(),S("mdc-switch--selected",r.checked)("mdc-switch--unselected",!r.checked)("mdc-switch--checked",r.checked)("mdc-switch--disabled",r.disabled)("mat-mdc-slide-toggle-disabled-interactive",r.disabledInteractive),g("tabIndex",r.disabled&&!r.disabledInteractive?-1:r.tabIndex)("disabled",r.disabled&&!r.disabledInteractive),O("id",r.buttonId)("name",r.name)("aria-label",r.ariaLabel)("aria-labelledby",r._getAriaLabelledBy())("aria-describedby",r.ariaDescribedby)("aria-required",r.required||null)("aria-checked",r.checked)("aria-disabled",r.disabled&&r.disabledInteractive?"true":null),o(9),g("matRippleTrigger",h)("matRippleDisabled",r.disableRipple||r.disabled)("matRippleCentered",!0),o(),p(r.hideIcon?-1:11),o(),g("for",r.buttonId),O("id",r._labelId)}},dependencies:[qe,lt],styles:[`.mdc-switch {
  align-items: center;
  background: none;
  border: none;
  cursor: pointer;
  display: inline-flex;
  flex-shrink: 0;
  margin: 0;
  outline: none;
  overflow: visible;
  padding: 0;
  position: relative;
  width: var(--mat-slide-toggle-track-width, 52px);
}
.mdc-switch.mdc-switch--disabled {
  cursor: default;
  pointer-events: none;
}
.mdc-switch.mat-mdc-slide-toggle-disabled-interactive {
  pointer-events: auto;
}

.mdc-switch__track {
  overflow: hidden;
  position: relative;
  width: 100%;
  height: var(--mat-slide-toggle-track-height, 32px);
  border-radius: var(--mat-slide-toggle-track-shape, var(--mat-sys-corner-full));
}
.mdc-switch--disabled.mdc-switch .mdc-switch__track {
  opacity: var(--mat-slide-toggle-disabled-track-opacity, 0.12);
}
.mdc-switch__track::before, .mdc-switch__track::after {
  border: 1px solid transparent;
  border-radius: inherit;
  box-sizing: border-box;
  content: "";
  height: 100%;
  left: 0;
  position: absolute;
  width: 100%;
  border-width: var(--mat-slide-toggle-track-outline-width, 2px);
  border-color: var(--mat-slide-toggle-track-outline-color, var(--mat-sys-outline));
}
.mdc-switch--selected .mdc-switch__track::before, .mdc-switch--selected .mdc-switch__track::after {
  border-width: var(--mat-slide-toggle-selected-track-outline-width, 2px);
  border-color: var(--mat-slide-toggle-selected-track-outline-color, transparent);
}
.mdc-switch--disabled .mdc-switch__track::before, .mdc-switch--disabled .mdc-switch__track::after {
  border-width: var(--mat-slide-toggle-disabled-unselected-track-outline-width, 2px);
  border-color: var(--mat-slide-toggle-disabled-unselected-track-outline-color, var(--mat-sys-on-surface));
}
@media (forced-colors: active) {
  .mdc-switch__track {
    border-color: currentColor;
  }
}
.mdc-switch__track::before {
  transition: transform 75ms 0ms cubic-bezier(0, 0, 0.2, 1);
  transform: translateX(0);
  background: var(--mat-slide-toggle-unselected-track-color, var(--mat-sys-surface-variant));
}
.mdc-switch--selected .mdc-switch__track::before {
  transition: transform 75ms 0ms cubic-bezier(0.4, 0, 0.6, 1);
  transform: translateX(100%);
}
[dir=rtl] .mdc-switch--selected .mdc-switch--selected .mdc-switch__track::before {
  transform: translateX(-100%);
}
.mdc-switch--selected .mdc-switch__track::before {
  opacity: var(--mat-slide-toggle-hidden-track-opacity, 0);
  transition: var(--mat-slide-toggle-hidden-track-transition, opacity 75ms);
}
.mdc-switch--unselected .mdc-switch__track::before {
  opacity: var(--mat-slide-toggle-visible-track-opacity, 1);
  transition: var(--mat-slide-toggle-visible-track-transition, opacity 75ms);
}
.mdc-switch:enabled:hover:not(:focus):not(:active) .mdc-switch__track::before {
  background: var(--mat-slide-toggle-unselected-hover-track-color, var(--mat-sys-surface-variant));
}
.mdc-switch:enabled:focus:not(:active) .mdc-switch__track::before {
  background: var(--mat-slide-toggle-unselected-focus-track-color, var(--mat-sys-surface-variant));
}
.mdc-switch:enabled:active .mdc-switch__track::before {
  background: var(--mat-slide-toggle-unselected-pressed-track-color, var(--mat-sys-surface-variant));
}
.mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:hover:not(:focus):not(:active) .mdc-switch__track::before, .mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:focus:not(:active) .mdc-switch__track::before, .mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:active .mdc-switch__track::before, .mdc-switch.mdc-switch--disabled .mdc-switch__track::before {
  background: var(--mat-slide-toggle-disabled-unselected-track-color, var(--mat-sys-surface-variant));
}
.mdc-switch__track::after {
  transform: translateX(-100%);
  background: var(--mat-slide-toggle-selected-track-color, var(--mat-sys-primary));
}
[dir=rtl] .mdc-switch__track::after {
  transform: translateX(100%);
}
.mdc-switch--selected .mdc-switch__track::after {
  transform: translateX(0);
}
.mdc-switch--selected .mdc-switch__track::after {
  opacity: var(--mat-slide-toggle-visible-track-opacity, 1);
  transition: var(--mat-slide-toggle-visible-track-transition, opacity 75ms);
}
.mdc-switch--unselected .mdc-switch__track::after {
  opacity: var(--mat-slide-toggle-hidden-track-opacity, 0);
  transition: var(--mat-slide-toggle-hidden-track-transition, opacity 75ms);
}
.mdc-switch:enabled:hover:not(:focus):not(:active) .mdc-switch__track::after {
  background: var(--mat-slide-toggle-selected-hover-track-color, var(--mat-sys-primary));
}
.mdc-switch:enabled:focus:not(:active) .mdc-switch__track::after {
  background: var(--mat-slide-toggle-selected-focus-track-color, var(--mat-sys-primary));
}
.mdc-switch:enabled:active .mdc-switch__track::after {
  background: var(--mat-slide-toggle-selected-pressed-track-color, var(--mat-sys-primary));
}
.mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:hover:not(:focus):not(:active) .mdc-switch__track::after, .mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:focus:not(:active) .mdc-switch__track::after, .mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:active .mdc-switch__track::after, .mdc-switch.mdc-switch--disabled .mdc-switch__track::after {
  background: var(--mat-slide-toggle-disabled-selected-track-color, var(--mat-sys-on-surface));
}

.mdc-switch__handle-track {
  height: 100%;
  pointer-events: none;
  position: absolute;
  top: 0;
  transition: transform 75ms 0ms cubic-bezier(0.4, 0, 0.2, 1);
  left: 0;
  right: auto;
  transform: translateX(0);
  width: calc(100% - var(--mat-slide-toggle-handle-width));
}
[dir=rtl] .mdc-switch__handle-track {
  left: auto;
  right: 0;
}
.mdc-switch--selected .mdc-switch__handle-track {
  transform: translateX(100%);
}
[dir=rtl] .mdc-switch--selected .mdc-switch__handle-track {
  transform: translateX(-100%);
}

.mdc-switch__handle {
  display: flex;
  pointer-events: auto;
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  left: 0;
  right: auto;
  transition: width 75ms cubic-bezier(0.4, 0, 0.2, 1), height 75ms cubic-bezier(0.4, 0, 0.2, 1), margin 75ms cubic-bezier(0.4, 0, 0.2, 1);
  width: var(--mat-slide-toggle-handle-width);
  height: var(--mat-slide-toggle-handle-height);
  border-radius: var(--mat-slide-toggle-handle-shape, var(--mat-sys-corner-full));
}
[dir=rtl] .mdc-switch__handle {
  left: auto;
  right: 0;
}
.mat-mdc-slide-toggle .mdc-switch--unselected .mdc-switch__handle {
  width: var(--mat-slide-toggle-unselected-handle-size, 16px);
  height: var(--mat-slide-toggle-unselected-handle-size, 16px);
  margin: var(--mat-slide-toggle-unselected-handle-horizontal-margin, 0 8px);
}
.mat-mdc-slide-toggle .mdc-switch--unselected .mdc-switch__handle:has(.mdc-switch__icons) {
  margin: var(--mat-slide-toggle-unselected-with-icon-handle-horizontal-margin, 0 4px);
}
.mat-mdc-slide-toggle .mdc-switch--selected .mdc-switch__handle {
  width: var(--mat-slide-toggle-selected-handle-size, 24px);
  height: var(--mat-slide-toggle-selected-handle-size, 24px);
  margin: var(--mat-slide-toggle-selected-handle-horizontal-margin, 0 24px);
}
.mat-mdc-slide-toggle .mdc-switch--selected .mdc-switch__handle:has(.mdc-switch__icons) {
  margin: var(--mat-slide-toggle-selected-with-icon-handle-horizontal-margin, 0 24px);
}
.mat-mdc-slide-toggle .mdc-switch__handle:has(.mdc-switch__icons) {
  width: var(--mat-slide-toggle-with-icon-handle-size, 24px);
  height: var(--mat-slide-toggle-with-icon-handle-size, 24px);
}
.mat-mdc-slide-toggle .mdc-switch:active:not(.mdc-switch--disabled) .mdc-switch__handle {
  width: var(--mat-slide-toggle-pressed-handle-size, 28px);
  height: var(--mat-slide-toggle-pressed-handle-size, 28px);
}
.mat-mdc-slide-toggle .mdc-switch--selected:active:not(.mdc-switch--disabled) .mdc-switch__handle {
  margin: var(--mat-slide-toggle-selected-pressed-handle-horizontal-margin, 0 22px);
}
.mat-mdc-slide-toggle .mdc-switch--unselected:active:not(.mdc-switch--disabled) .mdc-switch__handle {
  margin: var(--mat-slide-toggle-unselected-pressed-handle-horizontal-margin, 0 2px);
}
.mdc-switch--disabled.mdc-switch--selected .mdc-switch__handle::after {
  opacity: var(--mat-slide-toggle-disabled-selected-handle-opacity, 1);
}
.mdc-switch--disabled.mdc-switch--unselected .mdc-switch__handle::after {
  opacity: var(--mat-slide-toggle-disabled-unselected-handle-opacity, 0.38);
}
.mdc-switch__handle::before, .mdc-switch__handle::after {
  border: 1px solid transparent;
  border-radius: inherit;
  box-sizing: border-box;
  content: "";
  width: 100%;
  height: 100%;
  left: 0;
  position: absolute;
  top: 0;
  transition: background-color 75ms 0ms cubic-bezier(0.4, 0, 0.2, 1), border-color 75ms 0ms cubic-bezier(0.4, 0, 0.2, 1);
  z-index: -1;
}
@media (forced-colors: active) {
  .mdc-switch__handle::before, .mdc-switch__handle::after {
    border-color: currentColor;
  }
}
.mdc-switch--selected:enabled .mdc-switch__handle::after {
  background: var(--mat-slide-toggle-selected-handle-color, var(--mat-sys-on-primary));
}
.mdc-switch--selected:enabled:hover:not(:focus):not(:active) .mdc-switch__handle::after {
  background: var(--mat-slide-toggle-selected-hover-handle-color, var(--mat-sys-primary-container));
}
.mdc-switch--selected:enabled:focus:not(:active) .mdc-switch__handle::after {
  background: var(--mat-slide-toggle-selected-focus-handle-color, var(--mat-sys-primary-container));
}
.mdc-switch--selected:enabled:active .mdc-switch__handle::after {
  background: var(--mat-slide-toggle-selected-pressed-handle-color, var(--mat-sys-primary-container));
}
.mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled.mdc-switch--selected:hover:not(:focus):not(:active) .mdc-switch__handle::after, .mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled.mdc-switch--selected:focus:not(:active) .mdc-switch__handle::after, .mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled.mdc-switch--selected:active .mdc-switch__handle::after, .mdc-switch--selected.mdc-switch--disabled .mdc-switch__handle::after {
  background: var(--mat-slide-toggle-disabled-selected-handle-color, var(--mat-sys-surface));
}
.mdc-switch--unselected:enabled .mdc-switch__handle::after {
  background: var(--mat-slide-toggle-unselected-handle-color, var(--mat-sys-outline));
}
.mdc-switch--unselected:enabled:hover:not(:focus):not(:active) .mdc-switch__handle::after {
  background: var(--mat-slide-toggle-unselected-hover-handle-color, var(--mat-sys-on-surface-variant));
}
.mdc-switch--unselected:enabled:focus:not(:active) .mdc-switch__handle::after {
  background: var(--mat-slide-toggle-unselected-focus-handle-color, var(--mat-sys-on-surface-variant));
}
.mdc-switch--unselected:enabled:active .mdc-switch__handle::after {
  background: var(--mat-slide-toggle-unselected-pressed-handle-color, var(--mat-sys-on-surface-variant));
}
.mdc-switch--unselected.mdc-switch--disabled .mdc-switch__handle::after {
  background: var(--mat-slide-toggle-disabled-unselected-handle-color, var(--mat-sys-on-surface));
}
.mdc-switch__handle::before {
  background: var(--mat-slide-toggle-handle-surface-color);
}

.mdc-switch__shadow {
  border-radius: inherit;
  bottom: 0;
  left: 0;
  position: absolute;
  right: 0;
  top: 0;
}
.mdc-switch:enabled .mdc-switch__shadow {
  box-shadow: var(--mat-slide-toggle-handle-elevation-shadow);
}
.mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:hover:not(:focus):not(:active) .mdc-switch__shadow, .mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:focus:not(:active) .mdc-switch__shadow, .mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:active .mdc-switch__shadow, .mdc-switch.mdc-switch--disabled .mdc-switch__shadow {
  box-shadow: var(--mat-slide-toggle-disabled-handle-elevation-shadow);
}

.mdc-switch__ripple {
  left: 50%;
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%);
  z-index: -1;
  width: var(--mat-slide-toggle-state-layer-size, 40px);
  height: var(--mat-slide-toggle-state-layer-size, 40px);
}
.mdc-switch__ripple::after {
  content: "";
  opacity: 0;
}
.mdc-switch--disabled .mdc-switch__ripple::after {
  display: none;
}
.mat-mdc-slide-toggle-disabled-interactive .mdc-switch__ripple::after {
  display: block;
}
.mdc-switch:hover .mdc-switch__ripple::after {
  transition: 75ms opacity cubic-bezier(0, 0, 0.2, 1);
}
.mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:enabled:focus .mdc-switch__ripple::after, .mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:enabled:active .mdc-switch__ripple::after, .mat-mdc-slide-toggle-disabled-interactive.mdc-switch--disabled:enabled:hover:not(:focus) .mdc-switch__ripple::after, .mdc-switch--unselected:enabled:hover:not(:focus) .mdc-switch__ripple::after {
  background: var(--mat-slide-toggle-unselected-hover-state-layer-color, var(--mat-sys-on-surface));
  opacity: var(--mat-slide-toggle-unselected-hover-state-layer-opacity, var(--mat-sys-hover-state-layer-opacity));
}
.mdc-switch--unselected:enabled:focus .mdc-switch__ripple::after {
  background: var(--mat-slide-toggle-unselected-focus-state-layer-color, var(--mat-sys-on-surface));
  opacity: var(--mat-slide-toggle-unselected-focus-state-layer-opacity, var(--mat-sys-focus-state-layer-opacity));
}
.mdc-switch--unselected:enabled:active .mdc-switch__ripple::after {
  background: var(--mat-slide-toggle-unselected-pressed-state-layer-color, var(--mat-sys-on-surface));
  opacity: var(--mat-slide-toggle-unselected-pressed-state-layer-opacity, var(--mat-sys-pressed-state-layer-opacity));
  transition: opacity 75ms linear;
}
.mdc-switch--selected:enabled:hover:not(:focus) .mdc-switch__ripple::after {
  background: var(--mat-slide-toggle-selected-hover-state-layer-color, var(--mat-sys-primary));
  opacity: var(--mat-slide-toggle-selected-hover-state-layer-opacity, var(--mat-sys-hover-state-layer-opacity));
}
.mdc-switch--selected:enabled:focus .mdc-switch__ripple::after {
  background: var(--mat-slide-toggle-selected-focus-state-layer-color, var(--mat-sys-primary));
  opacity: var(--mat-slide-toggle-selected-focus-state-layer-opacity, var(--mat-sys-focus-state-layer-opacity));
}
.mdc-switch--selected:enabled:active .mdc-switch__ripple::after {
  background: var(--mat-slide-toggle-selected-pressed-state-layer-color, var(--mat-sys-primary));
  opacity: var(--mat-slide-toggle-selected-pressed-state-layer-opacity, var(--mat-sys-pressed-state-layer-opacity));
  transition: opacity 75ms linear;
}

.mdc-switch__icons {
  position: relative;
  height: 100%;
  width: 100%;
  z-index: 1;
  transform: translateZ(0);
}
.mdc-switch--disabled.mdc-switch--unselected .mdc-switch__icons {
  opacity: var(--mat-slide-toggle-disabled-unselected-icon-opacity, 0.38);
}
.mdc-switch--disabled.mdc-switch--selected .mdc-switch__icons {
  opacity: var(--mat-slide-toggle-disabled-selected-icon-opacity, 0.38);
}

.mdc-switch__icon {
  bottom: 0;
  left: 0;
  margin: auto;
  position: absolute;
  right: 0;
  top: 0;
  opacity: 0;
  transition: opacity 30ms 0ms cubic-bezier(0.4, 0, 1, 1);
}
.mdc-switch--unselected .mdc-switch__icon {
  width: var(--mat-slide-toggle-unselected-icon-size, 16px);
  height: var(--mat-slide-toggle-unselected-icon-size, 16px);
  fill: var(--mat-slide-toggle-unselected-icon-color, var(--mat-sys-surface-variant));
}
.mdc-switch--unselected.mdc-switch--disabled .mdc-switch__icon {
  fill: var(--mat-slide-toggle-disabled-unselected-icon-color, var(--mat-sys-surface-variant));
}
.mdc-switch--selected .mdc-switch__icon {
  width: var(--mat-slide-toggle-selected-icon-size, 16px);
  height: var(--mat-slide-toggle-selected-icon-size, 16px);
  fill: var(--mat-slide-toggle-selected-icon-color, var(--mat-sys-on-primary-container));
}
.mdc-switch--selected.mdc-switch--disabled .mdc-switch__icon {
  fill: var(--mat-slide-toggle-disabled-selected-icon-color, var(--mat-sys-on-surface));
}

.mdc-switch--selected .mdc-switch__icon--on,
.mdc-switch--unselected .mdc-switch__icon--off {
  opacity: 1;
  transition: opacity 45ms 30ms cubic-bezier(0, 0, 0.2, 1);
}

.mat-mdc-slide-toggle {
  -webkit-user-select: none;
  user-select: none;
  display: inline-block;
  -webkit-tap-highlight-color: transparent;
  outline: 0;
}
.mat-mdc-slide-toggle .mat-mdc-slide-toggle-ripple,
.mat-mdc-slide-toggle .mdc-switch__ripple::after {
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}
.mat-mdc-slide-toggle .mat-mdc-slide-toggle-ripple:not(:empty),
.mat-mdc-slide-toggle .mdc-switch__ripple::after:not(:empty) {
  transform: translateZ(0);
}
.mat-mdc-slide-toggle.mat-mdc-slide-toggle-focused .mat-focus-indicator::before {
  content: "";
}
.mat-mdc-slide-toggle .mat-internal-form-field {
  color: var(--mat-slide-toggle-label-text-color, var(--mat-sys-on-surface));
  font-family: var(--mat-slide-toggle-label-text-font, var(--mat-sys-body-medium-font));
  line-height: var(--mat-slide-toggle-label-text-line-height, var(--mat-sys-body-medium-line-height));
  font-size: var(--mat-slide-toggle-label-text-size, var(--mat-sys-body-medium-size));
  letter-spacing: var(--mat-slide-toggle-label-text-tracking, var(--mat-sys-body-medium-tracking));
  font-weight: var(--mat-slide-toggle-label-text-weight, var(--mat-sys-body-medium-weight));
}
.mat-mdc-slide-toggle .mat-ripple-element {
  opacity: 0.12;
}
.mat-mdc-slide-toggle .mat-focus-indicator::before {
  border-radius: 50%;
}
.mat-mdc-slide-toggle._mat-animation-noopable .mdc-switch__handle-track,
.mat-mdc-slide-toggle._mat-animation-noopable .mdc-switch__icon,
.mat-mdc-slide-toggle._mat-animation-noopable .mdc-switch__handle::before,
.mat-mdc-slide-toggle._mat-animation-noopable .mdc-switch__handle::after,
.mat-mdc-slide-toggle._mat-animation-noopable .mdc-switch__track::before,
.mat-mdc-slide-toggle._mat-animation-noopable .mdc-switch__track::after {
  transition: none;
}
.mat-mdc-slide-toggle .mdc-switch:enabled + .mdc-label {
  cursor: pointer;
}
.mat-mdc-slide-toggle .mdc-switch--disabled + label {
  color: var(--mat-slide-toggle-disabled-label-text-color, var(--mat-sys-on-surface));
}
.mat-mdc-slide-toggle label:empty {
  display: none;
}

.mat-mdc-slide-toggle-touch-target {
  position: absolute;
  top: 50%;
  left: 50%;
  height: var(--mat-slide-toggle-touch-target-size, 48px);
  width: 100%;
  transform: translate(-50%, -50%);
  display: var(--mat-slide-toggle-touch-target-display, block);
}
[dir=rtl] .mat-mdc-slide-toggle-touch-target {
  left: auto;
  right: 50%;
  transform: translate(50%, -50%);
}
`],encapsulation:2,changeDetection:0})}return t})(),rt=(()=>{class t{static \u0275fac=function(c){return new(c||t)};static \u0275mod=X({type:t});static \u0275inj=$({imports:[L,ke]})}return t})();var ut=()=>[],w=(t,a)=>a.id,_t=(t,a)=>a.value;function ft(t,a){t&1&&_(0,"mat-progress-bar",3)}function bt(t,a){if(t&1&&(n(0,"span")(1,"mat-icon"),l(2,"place"),i(),l(3),i()),t&2){let e=d();o(3),s(e.siteName)}}function vt(t,a){if(t&1&&(n(0,"span")(1,"mat-icon"),l(2,"person"),i(),l(3),i()),t&2){let e=d();o(3),s(e.contactName)}}function xt(t,a){if(t&1&&(n(0,"span")(1,"mat-icon"),l(2,"email"),i(),l(3),i()),t&2){let e=d();o(3),s(e.contactEmail)}}function Ct(t,a){if(t&1&&(n(0,"span")(1,"mat-icon"),l(2,"email"),i(),l(3),i()),t&2){let e=d();o(3),s(e.senderEmail)}}function wt(t,a){if(t&1&&(n(0,"mat-chip",14),l(1),i()),t&2){let e=d(),c=d();g("color",c.priorityColor(e.priority)),o(),s(e.priorityLabel)}}function yt(t,a){if(t&1&&(n(0,"mat-chip"),l(1),i()),t&2){let e=d();o(),s(e.typeLabel)}}function kt(t,a){if(t&1){let e=E();n(0,"div",15)(1,"mat-icon"),l(2,"warning"),i(),l(3," E-mail "),n(4,"strong"),l(5),i(),l(6," non rattach\xE9 \xE0 un contact. "),n(7,"button",45),u("click",function(){b(e);let r=d(2);return v(r.showAssign.set(!0))}),l(8,"Rattacher \u2192"),i()()}if(t&2){let e=d();o(5),s(e.senderEmail)}}function Mt(t,a){if(t&1&&(n(0,"mat-card",18)(1,"mat-card-header")(2,"mat-card-title"),l(3,"Description"),i()(),n(4,"mat-card-content")(5,"pre",46),l(6),i()()()),t&2){let e=d();o(6),s(e.description)}}function Et(t,a){t&1&&_(0,"mat-progress-bar",3)}function St(t,a){t&1&&(n(0,"p",21),l(1,"Aucune pi\xE8ce jointe."),i())}function Tt(t,a){if(t&1){let e=E();n(0,"div",23)(1,"a",47)(2,"mat-icon"),l(3),i(),n(4,"div",48)(5,"span",49),l(6),i(),n(7,"span",50),l(8),i()()(),n(9,"button",51),u("click",function(){let r=b(e).$implicit,h=d(),T=d();return v(T.removeAttachment(h.id,r.id))}),n(10,"mat-icon"),l(11,"delete"),i()()()}if(t&2){let e=a.$implicit;o(),g("href",e.url,P),o(2),s(e.image?"image":"description"),o(3),s(e.originalName),o(2),s(e.displaySize)}}function It(t,a){t&1&&(n(0,"p",21),l(1,"Aucun commentaire pour le moment."),i())}function Pt(t,a){t&1&&(n(0,"span",57),l(1,"Priv\xE9"),i())}function Dt(t,a){if(t&1&&(n(0,"div",59)(1,"mat-icon"),l(2,"email"),i(),l(3),i()),t&2){let e=d().$implicit;o(3),k(" ",e.sourceEmail)}}function Ot(t,a){if(t&1&&(n(0,"div",52)(1,"div",53)(2,"mat-icon",54),l(3),i(),n(4,"span",55),l(5),i(),n(6,"span",56),l(7),A(8,"date"),i(),m(9,Pt,2,0,"span",57),i(),n(10,"p",58),l(11),i(),m(12,Dt,4,1,"div",59),i()),t&2){let e=a.$implicit;S("private",!e.isPublic),o(3),s(e.isPublic?"chat":"lock"),o(2),s(e.authorName??"Syst\xE8me"),o(2),s(V(8,8,e.createdAt,"dd/MM/yyyy HH:mm")),o(2),p(e.isPublic?-1:9),o(2),s(e.content),o(),p(e.sourceEmail?12:-1)}}function zt(t,a){if(t&1&&(n(0,"mat-option",37),l(1),i()),t&2){let e=a.$implicit;g("value",e.value),o(),s(e.label)}}function Ft(t,a){if(t&1&&(n(0,"dt"),l(1,"Priorit\xE9"),i(),n(2,"dd")(3,"mat-chip",14),l(4),i()()),t&2){let e=d(),c=d();o(3),g("color",c.priorityColor(e.priority)),o(),s(e.priorityLabel)}}function Nt(t,a){if(t&1&&(n(0,"dt"),l(1,"Type"),i(),n(2,"dd"),l(3),i()),t&2){let e=d();o(3),s(e.typeLabel)}}function At(t,a){if(t&1&&(n(0,"dt"),l(1,"Site"),i(),n(2,"dd"),l(3),i()),t&2){let e=d(2);o(3),s(e.siteName)}}function Vt(t,a){if(t&1&&(n(0,"dt"),l(1,"Contact"),i(),n(2,"dd"),l(3),i()),t&2){let e=d(2);o(3),s(e.contactName)}}function Lt(t,a){if(t&1&&(n(0,"dl",39)(1,"dt"),l(2,"Client"),i(),n(3,"dd"),l(4),i(),m(5,At,4,1),m(6,Vt,4,1),n(7,"dt"),l(8,"\xC9quipe"),i(),n(9,"dd"),l(10),i(),n(11,"dt"),l(12,"Technicien"),i(),n(13,"dd"),l(14),i()()),t&2){let e=d();o(4),s(e.customerName??"\u2014"),o(),p(e.siteName?5:-1),o(),p(e.contactName?6:-1),o(4),s(e.teamName??"\u2014"),o(4),s(e.assignedToName??"\u2014")}}function Rt(t,a){if(t&1&&(n(0,"mat-option",37),l(1),i()),t&2){let e=a.$implicit;g("value",e.id),o(),s(e.name)}}function qt(t,a){if(t&1&&(n(0,"mat-option",37),l(1),i()),t&2){let e=a.$implicit;g("value",e.id),o(),s(e.name)}}function $t(t,a){if(t&1&&l(0),t&2){let e=d().$implicit;k(" (",e.email,") ")}}function Bt(t,a){if(t&1&&(n(0,"mat-option",37),l(1),m(2,$t,1,1),i()),t&2){let e=a.$implicit;g("value",e.id),o(),k(" ",e.fullName," "),o(),p(e.email?2:-1)}}function Gt(t,a){if(t&1){let e=E();n(0,"mat-form-field",61)(1,"mat-label"),l(2,"Site"),i(),n(3,"mat-select",65),u("selectionChange",function(r){b(e);let h=d(3);return v(h.onSiteChange(r.value))}),n(4,"mat-option",37),l(5,"\u2014"),i(),x(6,qt,2,2,"mat-option",37,w),i()(),n(8,"mat-form-field",61)(9,"mat-label"),l(10,"Contact"),i(),n(11,"mat-select",66)(12,"mat-option",37),l(13,"\u2014"),i(),x(14,Bt,3,3,"mat-option",37,w),i()()}if(t&2){let e=d(3);o(4),g("value",null),o(2),C(e.sites()),o(6),g("value",null),o(2),C(e.contacts())}}function Ut(t,a){if(t&1&&(n(0,"mat-option",37),l(1),i()),t&2){let e=a.$implicit;g("value",e.id),o(),s(e.name)}}function jt(t,a){if(t&1&&(n(0,"mat-option",37),l(1),i()),t&2){let e=a.$implicit;g("value",e.id),o(),s(e.fullName)}}function Ht(t,a){t&1&&(n(0,"mat-hint"),l(1,"Membres de l'\xE9quipe s\xE9lectionn\xE9e"),i())}function Xt(t,a){if(t&1){let e=E();n(0,"form",60),u("ngSubmit",function(){b(e);let r=d(),h=d();return v(h.submitAssign(r.id))}),n(1,"mat-form-field",61)(2,"mat-label"),l(3,"Client"),i(),n(4,"mat-select",62),u("selectionChange",function(r){b(e);let h=d(2);return v(h.onCustomerChange(r.value))}),n(5,"mat-option",37),l(6,"\u2014"),i(),x(7,Rt,2,2,"mat-option",37,w),i()(),m(9,Gt,16,2),_(10,"mat-divider"),n(11,"mat-form-field",61)(12,"mat-label"),l(13,"\xC9quipe"),i(),n(14,"mat-select",63),u("selectionChange",function(r){b(e);let h=d(2);return v(h.onTeamChange(r.value))}),n(15,"mat-option",37),l(16,"\u2014"),i(),x(17,Ut,2,2,"mat-option",37,w),i()(),n(19,"mat-form-field",61)(20,"mat-label"),l(21,"Technicien"),i(),n(22,"mat-select",64)(23,"mat-option",37),l(24,"\u2014"),i(),x(25,jt,2,2,"mat-option",37,w),i(),m(27,Ht,2,0,"mat-hint"),i(),n(28,"button",31)(29,"mat-icon"),l(30,"save"),i(),l(31," Enregistrer "),i()()}if(t&2){let e=d(2);g("formGroup",e.assignForm),o(5),g("value",null),o(2),C(e.customers()),o(2),p(e.assignForm.value.customerId?9:-1),o(6),g("value",null),o(2),C(e.teams()),o(6),g("value",null),o(2),C(e.availableUsers()),o(2),p(e.assignForm.value.teamId?27:-1),o(),g("disabled",e.saving())}}function Qt(t,a){t&1&&(n(0,"p",21),l(1,"Aucune intervention li\xE9e."),i())}function Wt(t,a){if(t&1&&(n(0,"span",69),l(1),i()),t&2){let e=d().$implicit;o(),s(e.statusLabel)}}function Yt(t,a){if(t&1&&(n(0,"a",42)(1,"mat-icon"),l(2,"build"),i(),n(3,"div",67)(4,"span",68),l(5),i(),m(6,Wt,2,1,"span",69),i()()),t&2){let e=a.$implicit;g("href","/intra/interventions/"+e.id,P),o(5),te("",e.reference||"#"+e.id," \u2014 ",e.title),o(),p(e.statusLabel?6:-1)}}function Zt(t,a){if(t&1&&(n(0,"div",70)(1,"mat-icon"),l(2),i(),l(3),i()),t&2){let e=d(2);S("ok",e.saveMsg()==="ok")("err",e.saveMsg()==="err"),o(2),s(e.saveMsg()==="ok"?"check_circle":"error"),o(),k(" ",e.saveMsg()==="ok"?"Enregistr\xE9.":"Erreur lors de la sauvegarde."," ")}}function Jt(t,a){if(t&1){let e=E();n(0,"div",5)(1,"mat-icon",6),l(2,"business"),i(),n(3,"div",7)(4,"div",8),l(5),i(),n(6,"div",9),m(7,bt,4,1,"span"),m(8,vt,4,1,"span"),m(9,xt,4,1,"span"),m(10,Ct,4,1,"span"),i()()(),n(11,"div",10)(12,"span",11),l(13),i(),n(14,"h1",12),l(15),i(),n(16,"div",13)(17,"mat-chip",14),l(18),i(),m(19,wt,2,2,"mat-chip",14),m(20,yt,2,1,"mat-chip"),i()(),m(21,kt,9,1,"div",15),n(22,"div",16)(23,"div",17),m(24,Mt,7,1,"mat-card",18),n(25,"mat-card",18)(26,"mat-card-header")(27,"mat-card-title"),l(28,"Pi\xE8ces jointes"),i(),n(29,"button",19),u("click",function(){b(e);let r=z(34);return v(r.click())}),n(30,"mat-icon"),l(31,"attach_file"),i(),l(32," Ajouter "),i(),n(33,"input",20,0),u("change",function(r){let h=b(e),T=d();return v(T.onFileSelected(r,h.id))}),i()(),n(35,"mat-card-content"),m(36,Et,1,0,"mat-progress-bar",3),m(37,St,2,0,"p",21),n(38,"div",22),x(39,Tt,12,4,"div",23,w),i()()(),n(41,"mat-card",18)(42,"mat-card-header")(43,"mat-card-title"),l(44,"Activit\xE9"),i()(),n(45,"mat-card-content"),m(46,It,2,0,"p",21),x(47,Ot,13,11,"div",24,w),_(49,"mat-divider",25),n(50,"form",26),u("ngSubmit",function(){let r=b(e),h=d();return v(h.submitComment(r.id))}),n(51,"mat-form-field",27)(52,"mat-label"),l(53,"Ajouter un commentaire"),i(),_(54,"textarea",28),i(),n(55,"div",29)(56,"mat-slide-toggle",30),l(57),i(),n(58,"button",31)(59,"mat-icon"),l(60,"send"),i(),l(61," Envoyer "),i()()()()()(),n(62,"aside",32)(63,"mat-card",18)(64,"mat-card-content",33)(65,"div",34),l(66,"Statut"),i(),n(67,"mat-form-field",35)(68,"mat-select",36),u("selectionChange",function(){let r=b(e),h=d();return v(h.changeStatus(r.id))}),x(69,zt,2,2,"mat-option",37,_t),i()(),_(71,"mat-divider",38),n(72,"dl",39),m(73,Ft,5,2),m(74,Nt,4,1),n(75,"dt"),l(76,"Cr\xE9\xE9 le"),i(),n(77,"dd"),l(78),A(79,"date"),i()()()(),n(80,"mat-card",18)(81,"mat-card-header")(82,"mat-card-title"),l(83,"Assignation"),i(),n(84,"button",40),u("click",function(){b(e);let r=d();return v(r.showAssign.set(!r.showAssign()))}),n(85,"mat-icon"),l(86),i()()(),n(87,"mat-card-content"),m(88,Lt,15,5,"dl",39)(89,Xt,32,7,"form",41),i()(),n(90,"mat-card",18)(91,"mat-card-header")(92,"mat-card-title"),l(93,"Interventions"),i()(),n(94,"mat-card-content"),m(95,Qt,2,0,"p",21),x(96,Yt,7,4,"a",42,w),n(98,"a",43)(99,"mat-icon"),l(100,"add"),i(),l(101," Cr\xE9er une intervention "),i()()(),m(102,Zt,4,6,"div",44),i()()}if(t&2){let e,c=a,r=d();o(5),s(c.customerName??"Aucun client rattach\xE9"),o(2),p(c.siteName?7:-1),o(),p(c.contactName?8:-1),o(),p(c.contactEmail?9:-1),o(),p(!c.siteName&&!c.contactName&&!c.contactEmail&&c.senderEmail?10:-1),o(3),k("#",c.id),o(2),s(c.title),o(2),g("color",r.statusColor(c.status)),o(),s(c.statusLabel),o(),p(c.priorityLabel?19:-1),o(),p(c.typeLabel?20:-1),o(),p(c.senderEmail&&!c.contactId?21:-1),o(3),p(c.description?24:-1),o(5),g("disabled",r.uploading()),o(7),p(r.uploading()?36:-1),o(),p(c.attachments.length===0&&!r.uploading()?37:-1),o(2),C(c.attachments),o(7),p(c.comments.length===0?46:-1),o(),C(c.comments),o(3),g("formGroup",r.commentForm),o(7),k(" ",r.commentForm.value.isPublic?"Public (visible client)":"Priv\xE9 (interne)"," "),o(),g("disabled",r.commentForm.invalid||r.saving()),o(10),g("formControl",r.statusCtrl),o(),C(((e=r.svc.meta.value())==null?null:e.statuses)||ne(33,ut)),o(4),p(c.priority?73:-1),o(),p(c.typeLabel?74:-1),o(4),s(V(79,30,c.createdAt,"dd/MM/yyyy HH:mm")),o(6),g("matTooltip",r.showAssign()?"Fermer":"Modifier"),o(2),s(r.showAssign()?"close":"edit"),o(2),p(r.showAssign()?89:88),o(7),p(c.interventions.length===0?95:-1),o(),C(c.interventions),o(2),g("href","/intra/tickets/"+c.id,P),o(4),p(r.saveMsg()?102:-1)}}function Kt(t,a){t&1&&(n(0,"div",4)(1,"mat-icon"),l(2,"help_outline"),i(),n(3,"p"),l(4,"Ticket introuvable."),i(),n(5,"a",71),l(6,"Retour \xE0 la liste"),i()())}var ei={NEW:"primary",IN_PROGRESS:"accent",CLOSED:"",CANCELLED:"warn"},ti={LOW:"",MEDIUM:"primary",HIGH:"accent",URGENT:"warn"},ct=class t{svc=f(at);route=f(de);fb=f(we);id=Number(this.route.snapshot.paramMap.get("id"));ticket=ae({params:()=>this.id,loader:({params:a})=>R(this.svc.getById(a))});saving=y(!1);uploading=y(!1);saveMsg=y(null);showAssign=y(!1);sites=y([]);contacts=y([]);customers=I(()=>this.svc.formMeta.value()?.customers??[]);teams=I(()=>this.svc.formMeta.value()?.teams??[]);users=I(()=>this.svc.formMeta.value()?.users??[]);statusCtrl=new fe("");commentForm=this.fb.group({content:["",he.required],isPublic:[!0]});assignForm=this.fb.group({customerId:[null],siteId:[null],contactId:[null],teamId:[null],assignedToId:[null]});teamIdSig=ot(this.assignForm.controls.teamId.valueChanges,{initialValue:this.assignForm.controls.teamId.value});availableUsers=I(()=>{let a=this.teamIdSig(),e=this.users();if(!a)return e;let c=this.teams().find(r=>r.id===a);return c?e.filter(r=>c.memberIds.includes(r.id)):e});constructor(){U(()=>{let a=this.ticket.value();a&&(this.statusCtrl.setValue(a.status,{emitEvent:!1}),this.assignForm.patchValue({customerId:a.customerId,siteId:a.siteId,contactId:a.contactId,teamId:a.teamId,assignedToId:a.assignedToId},{emitEvent:!1}),a.customerId&&(this.loadSites(a.customerId),this.loadContacts(a.customerId,a.siteId??void 0)))})}changeStatus(a){let e=this.statusCtrl.value;e&&(this.saving.set(!0),this.svc.updateStatus(a,e).subscribe({next:()=>{this.saving.set(!1),this.ticket.reload(),this.showMsg("ok")},error:()=>{this.saving.set(!1),this.showMsg("err")}}))}submitComment(a){if(this.commentForm.invalid)return;let{content:e,isPublic:c}=this.commentForm.value;this.saving.set(!0),this.svc.addComment(a,e,c??!0).subscribe({next:()=>{this.saving.set(!1),this.commentForm.reset({content:"",isPublic:!0}),this.ticket.reload(),this.showMsg("ok")},error:()=>{this.saving.set(!1),this.showMsg("err")}})}submitAssign(a){let e=this.assignForm.value,c={customerId:e.customerId,siteId:e.siteId,contactId:e.contactId,teamId:e.teamId,assignedToId:e.assignedToId};this.saving.set(!0),this.svc.assign(a,c).subscribe({next:()=>{this.saving.set(!1),this.showAssign.set(!1),this.ticket.reload(),this.showMsg("ok")},error:()=>{this.saving.set(!1),this.showMsg("err")}})}onCustomerChange(a){this.assignForm.patchValue({siteId:null,contactId:null}),this.sites.set([]),this.contacts.set([]),a&&(this.loadSites(a),this.loadContacts(a))}onSiteChange(a){this.assignForm.patchValue({contactId:null});let e=this.assignForm.value.customerId;e&&this.loadContacts(e,a??void 0)}onTeamChange(a){let e=this.assignForm.value.assignedToId;if(a&&e){let c=this.teams().find(r=>r.id===a);c&&!c.memberIds.includes(e)&&this.assignForm.patchValue({assignedToId:null})}}onFileSelected(a,e){let c=a.target,r=c.files?.[0];r&&(this.uploading.set(!0),this.svc.uploadAttachment(e,r).subscribe({next:()=>{this.uploading.set(!1),this.ticket.reload(),c.value=""},error:()=>{this.uploading.set(!1),this.showMsg("err"),c.value=""}}))}removeAttachment(a,e){this.svc.deleteAttachment(a,e).subscribe({next:()=>{this.ticket.reload(),this.showMsg("ok")},error:()=>this.showMsg("err")})}loadSites(a){this.svc.getSites(a).subscribe(e=>this.sites.set(e))}loadContacts(a,e){this.svc.getContacts(a,e).subscribe(c=>this.contacts.set(c))}showMsg(a){this.saveMsg.set(a),setTimeout(()=>this.saveMsg.set(null),3e3)}statusColor(a){return ei[a]??""}priorityColor(a){return ti[a]??""}static \u0275fac=function(e){return new(e||t)};static \u0275cmp=D({type:t,selectors:[["app-ticket-detail"]],decls:8,vars:3,consts:[["fileInput",""],[1,"breadcrumb"],["mat-button","","routerLink",".."],["mode","indeterminate"],[1,"not-found"],[1,"client-banner"],[1,"client-icon"],[1,"client-info"],[1,"client-name"],[1,"client-sub"],[1,"ticket-header"],[1,"ticket-id"],[1,"ticket-title"],[1,"header-chips"],["highlighted","",3,"color"],[1,"email-alert"],[1,"ticket-layout"],[1,"main-col"],["appearance","outlined",1,"mb-16"],["mat-stroked-button","",2,"margin-left","auto",3,"click","disabled"],["type","file","hidden","",3,"change"],[1,"empty-block"],[1,"attach-grid"],[1,"attach-item"],[1,"comment-item",3,"private"],[1,"comment-divider"],[1,"comment-form",3,"ngSubmit","formGroup"],["appearance","outline",2,"width","100%"],["matInput","","formControlName","content","rows","3","placeholder","\xC9crivez un commentaire..."],[1,"comment-actions"],["formControlName","isPublic","color","primary"],["mat-flat-button","","type","submit",3,"disabled"],[1,"side-col"],[1,"info-card-content"],[1,"info-label"],["appearance","outline","subscriptSizing","dynamic",2,"width","100%"],[3,"selectionChange","formControl"],[3,"value"],[1,"info-divider"],[1,"info-grid"],["mat-icon-button","",2,"margin-left","auto",3,"click","matTooltip"],[1,"assign-form",3,"formGroup"],["target","_blank",1,"linked-item",3,"href"],["target","_blank",1,"add-link",3,"href"],[1,"save-msg",3,"ok","err"],["mat-button","",3,"click"],[1,"description"],["target","_blank",1,"attach-link",3,"href"],[1,"attach-meta"],[1,"attach-name"],[1,"attach-size"],["mat-icon-button","","matTooltip","Supprimer",3,"click"],[1,"comment-item"],[1,"comment-meta"],[1,"comment-icon"],[1,"comment-author"],[1,"comment-date"],[1,"private-badge"],[1,"comment-content"],[1,"comment-source"],[1,"assign-form",3,"ngSubmit","formGroup"],["appearance","outline","subscriptSizing","dynamic"],["formControlName","customerId",3,"selectionChange"],["formControlName","teamId",3,"selectionChange"],["formControlName","assignedToId"],["formControlName","siteId",3,"selectionChange"],["formControlName","contactId"],[1,"linked-meta"],[1,"linked-title"],[1,"linked-sub"],[1,"save-msg"],["mat-stroked-button","","routerLink",".."]],template:function(e,c){if(e&1&&(n(0,"div",1)(1,"a",2)(2,"mat-icon"),l(3,"arrow_back"),i(),l(4," Tickets "),i()(),m(5,ft,1,0,"mat-progress-bar",3),m(6,Jt,103,34),m(7,Kt,7,0,"div",4)),e&2){let r;o(5),p(c.ticket.isLoading()?5:-1),o(),p((r=c.ticket.value())?6:-1,r),o(),p(!c.ticket.isLoading()&&!c.ticket.value()?7:-1)}},dependencies:[se,ye,be,pe,ue,_e,ve,Ce,xe,Ie,Me,Se,Te,Ee,Je,Ze,Qe,Xe,Ue,Ge,Be,tt,Ae,Fe,Ne,et,Ke,Ve,Re,Le,nt,it,He,je,Ye,We,rt,L,ce],styles:['@charset "UTF-8";.breadcrumb[_ngcontent-%COMP%]{margin-bottom:8px}.client-banner[_ngcontent-%COMP%]{display:flex;align-items:center;gap:12px;padding:12px 16px;margin-bottom:16px;background:#f0f4ff;border:1px solid #c5d3f5;border-radius:8px}.client-icon[_ngcontent-%COMP%]{color:#3f51b5;font-size:28px;width:28px;height:28px}.client-info[_ngcontent-%COMP%]{min-width:0}.client-name[_ngcontent-%COMP%]{font-size:16px;font-weight:600;color:#1a237e}.client-sub[_ngcontent-%COMP%]{display:flex;gap:14px;flex-wrap:wrap;margin-top:2px;font-size:12px;color:#5c6bc0}.client-sub[_ngcontent-%COMP%]   span[_ngcontent-%COMP%]{display:inline-flex;align-items:center;gap:3px}.client-sub[_ngcontent-%COMP%]   mat-icon[_ngcontent-%COMP%]{font-size:13px;width:13px;height:13px}.ticket-header[_ngcontent-%COMP%]{display:flex;align-items:flex-start;gap:12px;flex-wrap:wrap;margin-bottom:16px}.ticket-id[_ngcontent-%COMP%]{font-size:13px;color:#888;font-weight:500;padding-top:6px}.ticket-title[_ngcontent-%COMP%]{margin:0;font-size:22px;font-weight:600;flex:1;min-width:0}.header-chips[_ngcontent-%COMP%]{display:flex;gap:6px;flex-wrap:wrap;padding-top:4px}.email-alert[_ngcontent-%COMP%]{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:10px 14px;margin-bottom:16px;background:#fff3e0;border-left:4px solid #ff9800;border-radius:0 6px 6px 0;font-size:13px}.email-alert[_ngcontent-%COMP%]   mat-icon[_ngcontent-%COMP%]{color:#ff9800}.ticket-layout[_ngcontent-%COMP%]{display:grid;grid-template-columns:1fr 300px;gap:20px;align-items:start}.mb-16[_ngcontent-%COMP%]{margin-bottom:16px}pre.description[_ngcontent-%COMP%]{white-space:pre-wrap;word-break:break-word;font-family:inherit;font-size:14px;line-height:1.6;margin:0;color:#333}.empty-block[_ngcontent-%COMP%]{text-align:center;color:#aaa;padding:12px 0;font-size:13px}.attach-grid[_ngcontent-%COMP%]{display:flex;flex-direction:column;gap:6px}.attach-item[_ngcontent-%COMP%]{display:flex;align-items:center;gap:4px;border:1px solid #eee;border-radius:6px;padding:6px 8px}.attach-link[_ngcontent-%COMP%]{display:flex;align-items:center;gap:8px;flex:1;min-width:0;text-decoration:none;color:inherit}.attach-link[_ngcontent-%COMP%]   mat-icon[_ngcontent-%COMP%]{color:#5c6bc0}.attach-meta[_ngcontent-%COMP%]{display:flex;flex-direction:column;min-width:0}.attach-name[_ngcontent-%COMP%]{font-size:13px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.attach-size[_ngcontent-%COMP%]{font-size:11px;color:#999}.comment-item[_ngcontent-%COMP%]{padding:12px 0;border-bottom:1px solid #f0f0f0}.comment-item[_ngcontent-%COMP%]:last-of-type{border-bottom:none}.comment-item.private[_ngcontent-%COMP%]{background:#fffde7;padding:10px 12px;border-radius:6px;margin-bottom:4px;border-bottom:none}.comment-meta[_ngcontent-%COMP%]{display:flex;align-items:center;gap:8px;margin-bottom:6px;flex-wrap:wrap;font-size:12px;color:#888}.comment-icon[_ngcontent-%COMP%]{font-size:16px;width:16px;height:16px}.comment-author[_ngcontent-%COMP%]{font-weight:500;color:#444}.private-badge[_ngcontent-%COMP%]{background:#fff9c4;color:#f57f17;border-radius:4px;padding:1px 6px;font-size:11px;font-weight:500}.comment-content[_ngcontent-%COMP%]{margin:0;font-size:13px;line-height:1.5;white-space:pre-wrap}.comment-source[_ngcontent-%COMP%]{display:flex;align-items:center;gap:4px;font-size:11px;color:#999;margin-top:4px}.comment-source[_ngcontent-%COMP%]   mat-icon[_ngcontent-%COMP%]{font-size:12px;width:12px;height:12px}.comment-divider[_ngcontent-%COMP%]{margin:16px 0 12px}.comment-form[_ngcontent-%COMP%]{display:flex;flex-direction:column;gap:8px}.comment-actions[_ngcontent-%COMP%]{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px}.info-card-content[_ngcontent-%COMP%]{padding:12px 16px!important}.info-label[_ngcontent-%COMP%]{font-size:12px;font-weight:500;color:#666;margin-bottom:6px}.info-divider[_ngcontent-%COMP%]{margin:12px 0}.info-grid[_ngcontent-%COMP%]{display:grid;grid-template-columns:80px 1fr;gap:8px 12px;font-size:13px;margin:0}.info-grid[_ngcontent-%COMP%]   dt[_ngcontent-%COMP%]{font-weight:500;color:#666}.info-grid[_ngcontent-%COMP%]   dd[_ngcontent-%COMP%]{margin:0;word-break:break-word}.assign-form[_ngcontent-%COMP%]{display:flex;flex-direction:column;gap:8px;padding-top:4px}.assign-form[_ngcontent-%COMP%]   mat-form-field[_ngcontent-%COMP%]{width:100%}.linked-item[_ngcontent-%COMP%]{display:flex;align-items:center;gap:8px;padding:8px 0;border-bottom:1px solid #f0f0f0;text-decoration:none;color:inherit}.linked-item[_ngcontent-%COMP%]:last-of-type{border-bottom:none}.linked-item[_ngcontent-%COMP%]   mat-icon[_ngcontent-%COMP%]{color:#fd7e14}.linked-meta[_ngcontent-%COMP%]{display:flex;flex-direction:column;min-width:0}.linked-title[_ngcontent-%COMP%]{font-size:13px;font-weight:500}.linked-sub[_ngcontent-%COMP%]{font-size:11px;color:#999}.add-link[_ngcontent-%COMP%]{display:flex;align-items:center;gap:4px;margin-top:8px;font-size:13px;color:#3f51b5;text-decoration:none}.add-link[_ngcontent-%COMP%]   mat-icon[_ngcontent-%COMP%]{font-size:18px;width:18px;height:18px}.save-msg[_ngcontent-%COMP%]{display:flex;align-items:center;gap:6px;padding:8px 12px;border-radius:6px;font-size:13px}.save-msg[_ngcontent-%COMP%]   mat-icon[_ngcontent-%COMP%]{font-size:16px;width:16px;height:16px}.save-msg.ok[_ngcontent-%COMP%]{background:#e8f5e9;color:#2e7d32}.save-msg.err[_ngcontent-%COMP%]{background:#fdecea;color:#c62828}.not-found[_ngcontent-%COMP%]{display:flex;flex-direction:column;align-items:center;padding:64px 0;color:#aaa}.not-found[_ngcontent-%COMP%]   mat-icon[_ngcontent-%COMP%]{font-size:48px;width:48px;height:48px;margin-bottom:12px}@media(max-width:900px){.ticket-layout[_ngcontent-%COMP%]{grid-template-columns:1fr}}']})};export{ct as TicketDetailComponent};
